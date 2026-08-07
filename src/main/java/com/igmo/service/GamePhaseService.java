package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GuessSubmissionResult;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.SamplePrompt;
import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.PerfectGuessAlreadyConfirmedException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.GameResultSnapshot;
import com.igmo.web.dto.GuessSubmissionSnapshot;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GamePhaseService {

    private final GameRoomRepository gameRoomRepository;
    private final GamePhaseScheduler gamePhaseScheduler;
    private final GameEventPublisher eventPublisher;
    private final ImageGenerationService imageGenerationService;
    private final SamplePromptProvider samplePromptProvider;

    @Value("${igmo.game.prompt-duration}")
    private Duration promptDuration;
    @Value("${igmo.game.guess-duration}")
    private Duration guessDuration;
    @Value("${igmo.game.vote-duration}")
    private Duration voteDuration;
    @Value("${igmo.game.result-duration}")
    private Duration resultDuration;
    @Value("${igmo.game.image-generation-completion-delay}")
    private Duration imageGenerationCompletionDelay;

    public GamePhaseService(
            GameRoomRepository gameRoomRepository,
            GamePhaseScheduler gamePhaseScheduler,
            GameEventPublisher eventPublisher,
            ImageGenerationService imageGenerationService,
            SamplePromptProvider samplePromptProvider
    ) {
        this.gameRoomRepository = gameRoomRepository;
        this.gamePhaseScheduler = gamePhaseScheduler;
        this.eventPublisher = eventPublisher;
        this.imageGenerationService = imageGenerationService;
        this.samplePromptProvider = samplePromptProvider;
    }

    public void startGame(String code, String playerId) {
        PromptSubmissionSnapshot promptSnapshot = gameRoomRepository.update(code, room -> {
            GamePhase fromPhase = room.getPhase();
            room.changePlayerReady(playerId, true);
            room.start(playerId, Instant.now(), promptDuration);
            logPhaseTransition(code, fromPhase, room.getPhase());
            schedulePromptExpiration(room.getCode(), room.getPromptDeadline());
            return PromptSubmissionSnapshot.from(room);
        });
        eventPublisher.publishPromptSubmission(code, promptSnapshot);
    }

    public void submitPrompt(String code, String playerId, String prompt) {
        String submittedPrompt = prompt.trim();

        ImageGenerationEvent eventSnapshot = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            room.submitPrompt(playerId, submittedPrompt, submittedAt);
            return new ImageGenerationEvent(code, PromptEntryStatus.GENERATING, submittedPrompt, null);
        });

        eventPublisher.sendImageGenerationEvent(playerId, eventSnapshot);
        startImageGeneration(code, playerId, submittedPrompt);
    }

    public void submitGuess(String code, String playerId, String guess) {
        GuessSubmissionPublication result = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            GuessSubmissionSnapshot snapshot;
            try {
                GuessSubmissionResult guessSubmissionResult = room.submitGuess(playerId, guess, submittedAt);
                if (guessSubmissionResult == GuessSubmissionResult.PERFECT_RETRY_REQUIRED) {
                    return new GuessSubmissionPublication(
                            GuessSubmissionSnapshot.perfect(room, guess),
                            null,
                            room.getPhase()
                    );
                }
                snapshot = GuessSubmissionSnapshot.submitted(room, guess);
            } catch (DuplicateGuessSubmissionException
                     | GuessMatchesOthersException
                     | PerfectGuessAlreadyConfirmedException exception) {
                return new GuessSubmissionPublication(
                        GuessSubmissionSnapshot.rejected(
                                room,
                                guess,
                                exception.getMessage()
                        ),
                        null,
                        room.getPhase()
                );
            }
            if (room.hasAllCurrentRoundGuesses()) {
                gamePhaseScheduler.cancelGuess(code);
                return new GuessSubmissionPublication(
                        snapshot,
                        completeGuessSubmission(code, room, submittedAt),
                        room.getPhase()
                );
            }
            return new GuessSubmissionPublication(
                    snapshot,
                    RoomMessage.roundSnapshot(RoundSnapshot.from(room)),
                    room.getPhase());
        });
        if (result.hasRoomMessage()) {
            eventPublisher.publish(code, result.roomMessage());
        }
        eventPublisher.sendGuessSubmission(playerId, result.phase(), result.snapshot());
    }

    public void submitVote(String code, String playerId, String optionId) {
        RoomMessage<?> message = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            GamePhase fromPhase = room.getPhase();
            Instant submittedAt = Instant.now();
            room.submitVote(playerId, optionId, submittedAt);
            if (room.hasAllCurrentRoundVotes()) {
                gamePhaseScheduler.cancelVote(code);
                room.completeVoting(submittedAt, resultDuration);
                logPhaseTransition(code, fromPhase, room.getPhase());
                scheduleResultExpiration(code, room.getResultDeadline());
                return RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room));
            }
            logPhaseTransition(code, fromPhase, room.getPhase());
            return RoomMessage.voteSnapshot(VoteSnapshot.from(room));
        });
        eventPublisher.publish(code, message);
    }

    private record GuessSubmissionPublication(
            GuessSubmissionSnapshot snapshot,
            RoomMessage<?> roomMessage,
            GamePhase phase
    ) {
        private boolean hasRoomMessage() {
            return roomMessage != null;
        }
    }

    private RoomMessage<?> completeGuessSubmission(String code, GameRoom room, Instant completedAt) {
        GamePhase fromPhase = room.getPhase();
        room.completeGuessSubmission(completedAt, voteDuration);
        logPhaseTransition(code, fromPhase, room.getPhase());
        if (room.hasAllCurrentRoundVotes()) {
            fromPhase = room.getPhase();
            room.completeVoting(completedAt, resultDuration);
            logPhaseTransition(code, fromPhase, room.getPhase());
            scheduleResultExpiration(code, room.getResultDeadline());
            return RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room));
        }
        scheduleVoteExpiration(code, room.getVoteDeadline());
        sendOwnVoteOptions(code, room);
        return RoomMessage.voteSnapshot(VoteSnapshot.from(room));
    }

    private void sendOwnVoteOptions(String code, GameRoom room) {
        int roundNumber = room.getCurrentRound().getRoundNumber();
        room.getCurrentRoundOwnVoteOptions().forEach((playerId, ownVoteOption) ->
                eventPublisher.sendOwnVoteOption(playerId, OwnVoteOptionNotice.of(code, roundNumber, ownVoteOption)));
    }

    private void schedulePromptExpiration(String code, Instant deadline) {
        gamePhaseScheduler.schedulePrompt(code, deadline, () -> runPromptExpiration(code, deadline));
    }

    private void runPromptExpiration(String code, Instant deadline) {
        boolean shouldSchedulePlayingTransition = gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isPromptExpirationStale(deadline)) {
                        return false;
                    }
                    Map<String, SamplePrompt> assignments =
                            lockedRoom.fillMissingImagesWithSamples(samplePromptProvider.getAll(), Instant.now());
                    publishSampleImageResults(code, assignments);
                    eventPublisher.publishPromptSubmission(code, PromptSubmissionSnapshot.from(lockedRoom));
                    return lockedRoom.hasAllImagesGenerated();
                })
                .orElse(false);
        if (shouldSchedulePlayingTransition) {
            schedulePlayingTransition(code);
        }
    }

    private void publishSampleImageResults(String code, Map<String, SamplePrompt> assignments) {
        assignments.forEach((playerId, sample) -> eventPublisher.sendImageGenerationEvent(
                playerId,
                new ImageGenerationEvent(code, PromptEntryStatus.READY, sample.prompt(), sample.imageUrl())));
    }

    private void scheduleGuessExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleGuess(code, deadline, () -> runGuessExpiration(code, deadline));
    }

    private void runGuessExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isGuessExpirationStale(deadline)) {
                        return null;
                    }
                    Instant expiredAt = Instant.now();
                    lockedRoom.autoSubmitGuesses(expiredAt);
                    return completeGuessSubmission(code, lockedRoom, expiredAt);
                })
                .ifPresent(message -> eventPublisher.publish(code, message));
    }

    private void scheduleVoteExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVote(code, deadline, () -> runVoteExpiration(code, deadline));
    }

    private void runVoteExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isVoteExpirationStale(deadline)) {
                        return null;
                    }
                    GamePhase fromPhase = lockedRoom.getPhase();
                    lockedRoom.completeVoting(Instant.now(), resultDuration);
                    logPhaseTransition(code, fromPhase, lockedRoom.getPhase());
                    scheduleResultExpiration(code, lockedRoom.getResultDeadline());
                    return RoundResultSnapshot.from(lockedRoom);
                })
                .ifPresent(snapshot -> eventPublisher.publishRoundResult(code, snapshot));
    }

    private void scheduleResultExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleResult(code, deadline, () -> runResultExpiration(code, deadline));
    }

    private void runResultExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isResultExpirationStale(deadline)) {
                        return null;
                    }
                    return advanceRoundAndPrepare(code, lockedRoom);
                })
                .ifPresent(message -> eventPublisher.publish(code, message));
    }

    private RoomMessage<?> advanceRoundAndPrepare(String code, GameRoom room) {
        GamePhase fromPhase = room.getPhase();
        room.advanceRound(Instant.now(), guessDuration);
        logPhaseTransition(code, fromPhase, room.getPhase());
        if (room.getPhase() == GamePhase.ENDED) {
            return RoomMessage.gameResultSnapshot(GameResultSnapshot.from(room));
        }
        scheduleGuessExpiration(code, room.getGuessDeadline());
        return RoomMessage.roundSnapshot(RoundSnapshot.from(room));
    }

    private void startImageGeneration(String code, String playerId, String prompt) {
        imageGenerationService.generate(
                code,
                playerId,
                prompt,
                imageUrl -> updateImageGenerationResult(
                        code,
                        playerId,
                        room -> room.completeImageGeneration(playerId, imageUrl),
                        PromptEntryStatus.READY,
                        prompt,
                        imageUrl,
                        null),
                exception -> handleImageGenerationFailure(code, playerId, prompt, exception));
    }

    private void updateImageGenerationResult(
            String code,
            String playerId,
            Consumer<GameRoom> operation,
            PromptEntryStatus status,
            String submittedPrompt,
            String imageUrl,
            String errorMessage
    ) {
        boolean shouldSchedulePlayingTransition = gameRoomRepository.updateIfPresent(code, lockedRoom -> {
            if (lockedRoom.getPhase() != GamePhase.GENERATING
                    || !lockedRoom.isImageGenerationInProgress(playerId)) {
                return false;
            }
            boolean wasAllImagesGenerated = lockedRoom.hasAllImagesGenerated();
            operation.accept(lockedRoom);
            eventPublisher.sendImageGenerationEvent(
                    playerId,
                    new ImageGenerationEvent(code, status, submittedPrompt, imageUrl, errorMessage));
            eventPublisher.publishPromptSubmission(code, PromptSubmissionSnapshot.from(lockedRoom));
            return !wasAllImagesGenerated && lockedRoom.hasAllImagesGenerated();
        }).orElse(false);
        if (shouldSchedulePlayingTransition) {
            gamePhaseScheduler.cancelPrompt(code);
            schedulePlayingTransition(code);
        }
    }

    private void handleImageGenerationFailure(String code, String playerId, String prompt, Exception exception) {
        boolean shouldSchedulePlayingTransition = gameRoomRepository.updateIfPresent(code, lockedRoom -> {
            if (lockedRoom.getPhase() != GamePhase.GENERATING
                    || !lockedRoom.isImageGenerationInProgress(playerId)) {
                return false;
            }
            boolean wasAllImagesGenerated = lockedRoom.hasAllImagesGenerated();
            Instant failedAt = Instant.now();
            lockedRoom.failImageGeneration(playerId);
            if (lockedRoom.isPromptExpired(failedAt)) {
                SamplePrompt sample = lockedRoom.fillFailedImageWithSample(
                        playerId, samplePromptProvider.getAll(), failedAt);
                eventPublisher.sendImageGenerationEvent(
                        playerId,
                        new ImageGenerationEvent(code, PromptEntryStatus.READY, sample.prompt(), sample.imageUrl()));
            } else {
                eventPublisher.sendImageGenerationEvent(
                        playerId,
                        new ImageGenerationEvent(
                                code, PromptEntryStatus.FAILED, prompt, null, failureMessage(exception)));
            }
            eventPublisher.publishPromptSubmission(code, PromptSubmissionSnapshot.from(lockedRoom));
            return !wasAllImagesGenerated && lockedRoom.hasAllImagesGenerated();
        }).orElse(false);
        if (shouldSchedulePlayingTransition) {
            gamePhaseScheduler.cancelPrompt(code);
            schedulePlayingTransition(code);
        }
    }

    private String failureMessage(Exception exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return "이미지 생성에 실패했습니다. 다시 시도해주세요.";
    }

    private void schedulePlayingTransition(String code) {
        gamePhaseScheduler.schedulePlayingTransition(
                code,
                Instant.now().plus(imageGenerationCompletionDelay),
                () -> runPlayingTransition(code));
    }

    private void runPlayingTransition(String code) {
        try {
            gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                        GamePhase fromPhase = lockedRoom.getPhase();
                        lockedRoom.advanceToPlaying();
                        logPhaseTransition(code, fromPhase, lockedRoom.getPhase());
                        return initializeRounds(code, lockedRoom, Instant.now());
                    })
                    .ifPresent(snapshot -> eventPublisher.publishRound(code, snapshot));
        } catch (ImagesNotReadyException | RoundStartNotAllowedException ignored) {
            log.debug("이미지 생성 완료 전환 조건이 충족되지 않아 무시한다. roomCode={}", code);
        }
    }

    private RoundSnapshot initializeRounds(String code, GameRoom room, Instant startedAt) {
        room.startRounds(startedAt, guessDuration);
        scheduleGuessExpiration(code, room.getGuessDeadline());
        return RoundSnapshot.from(room);
    }

    private void logPhaseTransition(String roomCode, GamePhase fromPhase, GamePhase toPhase) {
        if (fromPhase == toPhase) {
            return;
        }
        log.atInfo()
                .addKeyValue("event", "game_phase_transition_completed")
                .addKeyValue("roomCode", roomCode)
                .addKeyValue("fromPhase", fromPhase)
                .addKeyValue("toPhase", toPhase)
                .log();
    }

}
