package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.PromptSubmissionType;
import com.igmo.domain.SamplePrompt;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptPhaseService {

    private final GameRoomRepository gameRoomRepository;
    private final GamePhaseScheduler gamePhaseScheduler;
    private final GameEventPublisher eventPublisher;
    private final ImageGenerationService imageGenerationService;
    private final SamplePromptProvider samplePromptProvider;
    private final GuessPhaseService guessPhaseService;
    private final VoteResultPhaseService voteResultPhaseService;

    @Value("${igmo.game.prompt-duration}")
    private Duration promptDuration;
    @Value("${igmo.game.guess-duration}")
    private Duration guessDuration;
    @Value("${igmo.game.image-generation-completion-delay}")
    private Duration imageGenerationCompletionDelay;

    public void startGame(String code, String playerId) {
        PromptSubmissionSnapshot promptSnapshot = gameRoomRepository.update(code, room -> {
            GamePhase fromPhase = room.getPhase();
            room.changePlayerReady(playerId, true);
            room.start(playerId, Instant.now(), promptDuration);
            gamePhaseScheduler.cancelLobby(code);
            GamePhaseService.logPhaseTransition(code, fromPhase, room.getPhase());
            schedulePromptExpiration(room.getCode(), room.getFinalPromptSubmissionDeadline());
            return PromptSubmissionSnapshot.from(room);
        });
        eventPublisher.publishPromptSubmission(code, promptSnapshot);
    }

    public void submitPrompt(String code, String playerId, String prompt, PromptSubmissionType submissionType) {
        String submittedPrompt = prompt.trim();

        ImageGenerationEvent eventSnapshot = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            room.submitPrompt(playerId, submittedPrompt, submittedAt, submissionType);
            return new ImageGenerationEvent(code, PromptEntryStatus.GENERATING, submittedPrompt, null);
        });

        eventPublisher.sendImageGenerationEvent(playerId, eventSnapshot);
        startImageGeneration(code, playerId, submittedPrompt);
    }

    public void onPlayerRemoved(String code) {
        boolean shouldSchedulePlayingTransition = gameRoomRepository.updateIfPresent(code, room ->
                        room.getPhase() == GamePhase.GENERATING && room.hasAllImagesGenerated())
                .orElse(false);
        if (shouldSchedulePlayingTransition) {
            gamePhaseScheduler.cancelPrompt(code);
            schedulePlayingTransition(code);
        }
    }

    void schedulePromptExpiration(String code, Instant deadline) {
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
                        GamePhaseService.logPhaseTransition(code, fromPhase, lockedRoom.getPhase());
                        return initializeRounds(code, lockedRoom, Instant.now());
                    })
                    .ifPresent(snapshot -> eventPublisher.publishRound(code, snapshot));
        } catch (ImagesNotReadyException | RoundStartNotAllowedException ignored) {
            log.debug("이미지 생성 완료 전환 조건이 충족되지 않아 무시한다. roomCode={}", code);
        }
    }

    private RoundSnapshot initializeRounds(String code, GameRoom room, Instant startedAt) {
        room.startRounds(startedAt, guessDuration);
        guessPhaseService.scheduleGuessExpiration(
                code,
                room.getFinalGuessSubmissionDeadline(),
                voteResultPhaseService::completeGuessSubmission);
        return RoundSnapshot.from(room);
    }

}
