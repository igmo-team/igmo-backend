package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.service.exception.ImageStorageException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.GameRegistry;
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
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GameService {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";
    private static final String IMAGE_GENERATION_QUEUE = "/queue/image-generation";

    private final GameRegistry gameRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final GamePhaseScheduler gamePhaseScheduler;
    private final ImageGenerationClient imageGenerationClient;
    private final Executor imageGenerationExecutor;

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

    public GameService(
            GameRegistry gameRegistry,
            SimpMessagingTemplate messagingTemplate,
            GamePhaseScheduler gamePhaseScheduler,
            ImageGenerationClient imageGenerationClient,
            @Qualifier("imageGenerationExecutor") Executor imageGenerationExecutor
    ) {
        this.gameRegistry = gameRegistry;
        this.messagingTemplate = messagingTemplate;
        this.gamePhaseScheduler = gamePhaseScheduler;
        this.imageGenerationClient = imageGenerationClient;
        this.imageGenerationExecutor = imageGenerationExecutor;
    }

    public void startGame(String code, String playerId) {
        PromptSubmissionSnapshot promptSnapshot = withLockedRoom(code, room -> {
            room.changePlayerReady(playerId, true);
            room.start(playerId, Instant.now(), promptDuration);
            schedulePromptExpiration(room.getCode(), room.getPromptDeadline());
            return PromptSubmissionSnapshot.from(room);
        });
        broadcastPromptSubmissionSnapshot(code, promptSnapshot);
    }

    public void submitPrompt(String code, String playerId, String prompt) {
        PromptSubmissionSnapshot snapshot = withLockedRoom(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            Instant submittedAt = Instant.now();
            room.submitPrompt(playerId, prompt, submittedAt);
            if (!room.hasWaitingPrompt()) {
                gamePhaseScheduler.cancelPrompt(code);
            }
            return PromptSubmissionSnapshot.from(room);
        });
        broadcastPromptSubmissionSnapshot(code, snapshot);
        startImageGeneration(code, playerId, prompt);
    }

    public void submitGuess(String code, String playerId, String guess) {
        RoomMessage<?> message = withLockedRoom(code, room -> {
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
        broadcastRoomMessage(code, message);
    }

    public void submitVote(String code, String playerId, String optionId) {
        RoomMessage<?> message = withLockedRoom(code, room -> {
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
        broadcastRoomMessage(code, message);
    }

    private void schedulePromptExpiration(String code, Instant deadline) {
        gamePhaseScheduler.schedulePrompt(code, deadline, () -> runPromptExpiration(code, deadline));
    }

    private void runPromptExpiration(String code, Instant deadline) {
        gameRegistry.find(code)
                .map(room -> withLockedRoom(code, lockedRoom -> {
                    if (lockedRoom.isPromptExpirationStale(deadline)) {
                        return null;
                    }
                    Map<String, String> autoSubmittedPrompts = lockedRoom.autoSubmitPrompts(deadline);
                    return new PromptExpirationResult(
                            PromptSubmissionSnapshot.from(lockedRoom),
                            autoSubmittedPrompts);
                }))
                .ifPresent(result -> {
                    broadcastPromptSubmissionSnapshot(code, result.snapshot());
                    result.autoSubmittedPrompts()
                            .forEach((playerId, prompt) -> startImageGeneration(code, playerId, prompt));
                });
    }

    private void scheduleGuessExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleGuess(code, deadline, () -> runGuessExpiration(code, deadline));
    }

    private void runGuessExpiration(String code, Instant deadline) {
        gameRegistry.find(code)
                .map(room -> withLockedRoom(code, lockedRoom -> {
                    if (lockedRoom.isGuessExpirationStale(deadline)) {
                        return null;
                    }
                    lockedRoom.completeGuessSubmission(Instant.now(), voteDuration);
                    scheduleVoteExpiration(code, lockedRoom.getVoteDeadline());
                    return VoteSnapshot.from(lockedRoom);
                }))
                .ifPresent(snapshot -> broadcastVoteSnapshot(code, snapshot));
    }

    private void scheduleVoteExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVote(code, deadline, () -> runVoteExpiration(code, deadline));
    }

    private void runVoteExpiration(String code, Instant deadline) {
        gameRegistry.find(code)
                .map(room -> withLockedRoom(code, lockedRoom -> {
                    if (lockedRoom.isVoteExpirationStale(deadline)) {
                        return null;
                    }
                    lockedRoom.completeVoting(Instant.now(), resultDuration);
                    scheduleResultExpiration(code, lockedRoom.getResultDeadline());
                    return RoundResultSnapshot.from(lockedRoom);
                }))
                .ifPresent(snapshot -> broadcastRoundResultSnapshot(code, snapshot));
    }

    private void scheduleResultExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleResult(code, deadline, () -> runResultExpiration(code, deadline));
    }

    private void runResultExpiration(String code, Instant deadline) {
        gameRegistry.find(code)
                .map(room -> withLockedRoom(code, lockedRoom -> {
                    if (lockedRoom.isResultExpirationStale(deadline)) {
                        return null;
                    }
                    return advanceRoundAndPrepare(code, lockedRoom);
                }))
                .ifPresent(message -> broadcastRoomMessage(code, message));
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
        imageGenerationExecutor.execute(() -> runImageGeneration(code, playerId, prompt));
    }

    private void runImageGeneration(String code, String playerId, String prompt) {
        String submittedPrompt = prompt.trim();
        long startedAt = System.nanoTime();
        try {
            String imageUrl = imageGenerationClient.generate(submittedPrompt);
            completeImageGeneration(code, playerId, submittedPrompt, imageUrl, startedAt);
        } catch (Exception exception) {
            handleImageGenerationFailure(code, playerId, submittedPrompt, startedAt, exception);
        }
    }

    private void completeImageGeneration(
            String code,
            String playerId,
            String submittedPrompt,
            String imageUrl,
            long startedAt
    ) {
        updateImageGenerationResult(
                code,
                playerId,
                room -> room.completeImageGeneration(playerId, imageUrl),
                PromptEntryStatus.READY,
                submittedPrompt,
                imageUrl);
        log.info(
                "이미지 생성 완료. roomCode={}, playerId={}, durationMs={}",
                code,
                playerId,
                elapsedMillis(startedAt));
    }

    private void handleImageGenerationFailure(
            String code,
            String playerId,
            String submittedPrompt,
            long startedAt,
            Exception exception
    ) {
        logImageGenerationFailure(code, playerId, startedAt, exception);
        updateImageGenerationResult(
                code,
                playerId,
                room -> room.failImageGeneration(playerId),
                PromptEntryStatus.FAILED,
                submittedPrompt,
                null);
    }

    private void logImageGenerationFailure(String code, String playerId, long startedAt, Exception exception) {
        if (exception instanceof ImageStorageException) {
            log.warn(
                    "S3 이미지 저장 실패. roomCode={}, playerId={}, reason={}, durationMs={}",
                    code,
                    playerId,
                    exception.getMessage(),
                    elapsedMillis(startedAt),
                    exception);
            return;
        }
        log.warn(
                "이미지 생성 실패. roomCode={}, playerId={}, reason={}, durationMs={}",
                code,
                playerId,
                exception.getMessage(),
                elapsedMillis(startedAt),
                exception);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void updateImageGenerationResult(
            String code,
            String playerId,
            Consumer<GameRoom> operation,
            PromptEntryStatus status,
            String submittedPrompt,
            String imageUrl
    ) {
        try {
            boolean shouldSchedulePlayingTransition = gameRegistry.find(code)
                    .map(room -> withLockedRoom(code, lockedRoom -> {
                        if (lockedRoom.getPhase() != GamePhase.GENERATING) {
                            return false;
                        }
                        boolean wasAllImagesGenerated = lockedRoom.hasAllImagesGenerated();
                        operation.accept(lockedRoom);
                        sendImageGenerationResult(
                                playerId,
                                new ImageGenerationResult(code, status, submittedPrompt, imageUrl));
                        broadcastPromptSubmissionSnapshot(code, PromptSubmissionSnapshot.from(lockedRoom));
                        return !wasAllImagesGenerated && lockedRoom.hasAllImagesGenerated();
                    }))
                    .orElse(false);
            if (shouldSchedulePlayingTransition) {
                schedulePlayingTransition(code);
            }
        } catch (RoomNotFoundException ignored) {
            log.debug("이미지 생성 결과를 반영할 방이 없어 결과를 버린다. roomCode={}, playerId={}", code, playerId);
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
            gameRegistry.find(code)
                    .map(room -> withLockedRoom(code, lockedRoom -> {
                        lockedRoom.advanceToPlaying();
                        return initializeRounds(code, lockedRoom, Instant.now());
                    }))
                    .ifPresent(snapshot -> broadcastRoundSnapshot(code, snapshot));
        } catch (RoomNotFoundException | ImagesNotReadyException | RoundStartNotAllowedException ignored) {
            log.debug("이미지 생성 완료 전환 조건이 충족되지 않아 무시한다. roomCode={}", code);
        }
    }

    private RoundSnapshot initializeRounds(String code, GameRoom room, Instant startedAt) {
        room.startRounds(startedAt, guessDuration);
        scheduleGuessExpiration(code, room.getGuessDeadline());
        return RoundSnapshot.from(room);
    }

    private record PromptExpirationResult(
            PromptSubmissionSnapshot snapshot,
            Map<String, String> autoSubmittedPrompts
    ) {
    }

    private void broadcastPromptSubmissionSnapshot(String code, PromptSubmissionSnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.promptSubmissionSnapshot(snapshot));
    }

    private void broadcastRoundSnapshot(String code, RoundSnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.roundSnapshot(snapshot));
    }

    private void broadcastVoteSnapshot(String code, VoteSnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.voteSnapshot(snapshot));
    }

    private void broadcastRoundResultSnapshot(String code, RoundResultSnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.roundResultSnapshot(snapshot));
    }

    private void broadcastRoomMessage(String code, RoomMessage<?> message) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, message);
    }

    private void sendImageGenerationResult(String playerId, ImageGenerationResult result) {
        messagingTemplate.convertAndSendToUser(playerId, IMAGE_GENERATION_QUEUE, result);
    }

    private void withLockedRoom(String code, Consumer<GameRoom> operation) {
        withLockedRoom(code, room -> {
            operation.accept(room);
            return null;
        });
    }

    private <T> T withLockedRoom(String code, Function<GameRoom, T> operation) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            return operation.apply(room);
        }
    }

    private boolean isDetached(String code, GameRoom room) {
        return gameRegistry.find(code).orElse(null) != room;
    }
}
