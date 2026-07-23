package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.SamplePrompt;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.GameResultSnapshot;
import com.igmo.web.dto.ImageGenerationResult;
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
            room.changePlayerReady(playerId, true);
            room.start(playerId, Instant.now(), promptDuration);
            schedulePromptExpiration(room.getCode(), room.getPromptDeadline());
            return PromptSubmissionSnapshot.from(room);
        });
        eventPublisher.publishPromptSubmission(code, promptSnapshot);
    }

    public void submitPrompt(String code, String playerId, String prompt) {
        PromptSubmissionSnapshot snapshot = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            room.submitPrompt(playerId, prompt, submittedAt);
            return PromptSubmissionSnapshot.from(room);
        });
        eventPublisher.publishPromptSubmission(code, snapshot);
        startImageGeneration(code, playerId, prompt);
    }

    public void submitGuess(String code, String playerId, String guess) {
        RoomMessage<?> message = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            room.submitGuess(playerId, guess, submittedAt);
            if (room.hasAllCurrentRoundGuesses()) {
                gamePhaseScheduler.cancelGuess(code);
                room.completeGuessSubmission(submittedAt, voteDuration);
                scheduleVoteExpiration(code, room.getVoteDeadline());
                return RoomMessage.voteSnapshot(VoteSnapshot.from(room));
            }
            return RoomMessage.roundSnapshot(RoundSnapshot.from(room));
        });
        eventPublisher.publish(code, message);
    }

    public void submitVote(String code, String playerId, String optionId) {
        RoomMessage<?> message = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            room.submitVote(playerId, optionId, submittedAt);
            if (room.hasAllCurrentRoundVotes()) {
                gamePhaseScheduler.cancelVote(code);
                room.completeVoting(submittedAt, resultDuration);
                scheduleResultExpiration(code, room.getResultDeadline());
                return RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room));
            }
            return RoomMessage.voteSnapshot(VoteSnapshot.from(room));
        });
        eventPublisher.publish(code, message);
    }

    private void schedulePromptExpiration(String code, Instant deadline) {
        gamePhaseScheduler.schedulePrompt(code, deadline, () -> runPromptExpiration(code, deadline));
    }

    // 프롬프트 마감 시 READY가 아닌 참가자를 샘플로 채워 전원 READY를 보장하고 PLAYING 전환을 예약한다.
    private void runPromptExpiration(String code, Instant deadline) {
        boolean shouldSchedulePlayingTransition = gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isPromptExpirationStale(deadline)) {
                        return false;
                    }
                    Map<String, SamplePrompt> assignments =
                            lockedRoom.fillMissingImagesWithSamples(samplePromptProvider.getAll(), Instant.now());
                    publishSampleImageResults(code, assignments);
                    eventPublisher.publishPromptSubmission(code, PromptSubmissionSnapshot.from(lockedRoom));
                    return !assignments.isEmpty();
                })
                .orElse(false);
        if (shouldSchedulePlayingTransition) {
            schedulePlayingTransition(code);
        }
    }

    private void publishSampleImageResults(String code, Map<String, SamplePrompt> assignments) {
        assignments.forEach((playerId, sample) -> eventPublisher.sendImageGenerationResult(
                playerId,
                new ImageGenerationResult(code, PromptEntryStatus.READY, sample.prompt(), sample.imageUrl())));
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
                    lockedRoom.completeGuessSubmission(expiredAt, voteDuration);
                    scheduleVoteExpiration(code, lockedRoom.getVoteDeadline());
                    return VoteSnapshot.from(lockedRoom);
                })
                .ifPresent(snapshot -> eventPublisher.publishVote(code, snapshot));
    }

    private void scheduleVoteExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVote(code, deadline, () -> runVoteExpiration(code, deadline));
    }

    private void runVoteExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isVoteExpirationStale(deadline)) {
                        return null;
                    }
                    lockedRoom.completeVoting(Instant.now(), resultDuration);
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
        room.advanceRound(Instant.now(), guessDuration);
        if (room.getPhase() == GamePhase.ENDED) {
            return RoomMessage.gameResultSnapshot(GameResultSnapshot.from(room));
        }
        scheduleGuessExpiration(code, room.getGuessDeadline());
        return RoomMessage.roundSnapshot(RoundSnapshot.from(room));
    }

    private void startImageGeneration(String code, String playerId, String prompt) {
        String submittedPrompt = prompt.trim();
        imageGenerationService.generate(
                code,
                playerId,
                submittedPrompt,
                imageUrl -> updateImageGenerationResult(
                        code,
                        playerId,
                        room -> room.completeImageGeneration(playerId, imageUrl),
                        PromptEntryStatus.READY,
                        submittedPrompt,
                        imageUrl),
                exception -> updateImageGenerationResult(
                        code,
                        playerId,
                        room -> room.failImageGeneration(playerId),
                        PromptEntryStatus.FAILED,
                        submittedPrompt,
                        null));
    }

    private void updateImageGenerationResult(
            String code,
            String playerId,
            Consumer<GameRoom> operation,
            PromptEntryStatus status,
            String submittedPrompt,
            String imageUrl
    ) {
        boolean shouldSchedulePlayingTransition = gameRoomRepository.updateIfPresent(code, lockedRoom -> {
            // 마감 시 샘플로 채워진 뒤 뒤늦게 도착한 생성 결과는 무시한다. (엔트리가 더 이상 생성 중이 아님)
            if (lockedRoom.getPhase() != GamePhase.GENERATING
                    || !lockedRoom.isImageGenerationInProgress(playerId)) {
                return false;
            }
            boolean wasAllImagesGenerated = lockedRoom.hasAllImagesGenerated();
            operation.accept(lockedRoom);
            eventPublisher.sendImageGenerationResult(
                    playerId,
                    new ImageGenerationResult(code, status, submittedPrompt, imageUrl));
            eventPublisher.publishPromptSubmission(code, PromptSubmissionSnapshot.from(lockedRoom));
            return !wasAllImagesGenerated && lockedRoom.hasAllImagesGenerated();
        }).orElse(false);
        if (shouldSchedulePlayingTransition) {
            gamePhaseScheduler.cancelPrompt(code);
            schedulePlayingTransition(code);
        }
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
                        lockedRoom.advanceToPlaying();
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
}
