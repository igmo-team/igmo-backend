package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.service.exception.ImageStorageException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.ImageGenerationResult;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GameService {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";
    private static final String IMAGE_GENERATION_QUEUE = "/queue/image-generation";
    private static final int MAX_ROOM_CODE_ATTEMPTS = 10;

    private final GameRegistry gameRegistry;
    private final RoomCodeGenerator roomCodeGenerator;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler disconnectGraceScheduler;
    private final TaskScheduler promptDeadlineScheduler;
    private final TaskScheduler imageGenerationCompletionScheduler;
    private final ImageGenerationClient imageGenerationClient;
    private final Executor imageGenerationExecutor;

    @Value("${igmo.game.disconnect-grace}")
    private Duration disconnectGrace;
    @Value("${igmo.game.prompt-duration}")
    private Duration promptDuration;
    @Value("${igmo.game.image-generation-completion-delay}")
    private Duration imageGenerationCompletionDelay;

    private final Map<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingPromptExpirations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingPlayingTransitions = new ConcurrentHashMap<>();

    public GameService(GameRegistry gameRegistry,
                       RoomCodeGenerator roomCodeGenerator,
                       SimpMessagingTemplate messagingTemplate,
                       @Qualifier("disconnectGraceScheduler") TaskScheduler disconnectGraceScheduler,
                       @Qualifier("promptDeadlineScheduler") TaskScheduler promptDeadlineScheduler,
                       @Qualifier("imageGenerationCompletionScheduler") TaskScheduler imageGenerationCompletionScheduler,
                       ImageGenerationClient imageGenerationClient,
                       @Qualifier("imageGenerationExecutor") Executor imageGenerationExecutor) {
        this.gameRegistry = gameRegistry;
        this.roomCodeGenerator = roomCodeGenerator;
        this.messagingTemplate = messagingTemplate;
        this.disconnectGraceScheduler = disconnectGraceScheduler;
        this.promptDeadlineScheduler = promptDeadlineScheduler;
        this.imageGenerationCompletionScheduler = imageGenerationCompletionScheduler;
        this.imageGenerationClient = imageGenerationClient;
        this.imageGenerationExecutor = imageGenerationExecutor;
    }

    public CreateGameResponse createGame(String nickname) {
        Player host = new Player(nickname);
        GameRoom room = createRoomWithUniqueCode(host);
        return new CreateGameResponse(room.getCode(), host.getId(), host.getSecret(), LobbySnapshot.from(room));
    }

    public JoinGameResponse joinGame(String code, String nickname) {
        return withLockedRoom(code, room -> {
            Player player = new Player(nickname);
            room.addPlayer(player);
            LobbySnapshot snapshot = LobbySnapshot.from(room);
            broadcastLobbySnapshot(code, snapshot);
            return new JoinGameResponse(player.getId(), player.getSecret(), snapshot);
        });
    }

    public void leaveGame(String code, String playerId, String secret) {
        withLockedRoom(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            if (!room.isSecretValid(playerId, secret)) {
                throw new UnauthorizedPlayerException();
            }
            cancelPendingRemoval(code, playerId);
            removePlayerAndBroadcast(room, playerId);
        });
    }

    public void changeReady(String code, String playerId, boolean ready) {
        withLockedRoom(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            room.changePlayerReady(playerId, ready);
            broadcastLobbySnapshot(code, LobbySnapshot.from(room));
        });
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
                cancelPromptExpiration(code);
            }
            return PromptSubmissionSnapshot.from(room);
        });
        broadcastPromptSubmissionSnapshot(code, snapshot);
        startImageGeneration(code, playerId, prompt);
    }

    public void handleDisconnect(String code, String playerId) {
        ScheduledFuture<?> future = disconnectGraceScheduler.schedule(
                () -> runScheduledRemoval(code, playerId),
                Instant.now().plus(disconnectGrace));
        ScheduledFuture<?> previous = pendingRemovals.put(removalKey(code, playerId), future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void cancelPendingRemoval(String code, String playerId) {
        ScheduledFuture<?> future = pendingRemovals.remove(removalKey(code, playerId));
        if (future != null) {
            future.cancel(false);
        }
    }

    private void runScheduledRemoval(String code, String playerId) {
        // 취소 측이 먼저 키를 지웠으면 경합에서 진 것이므로 제거하지 않는다.
        if (pendingRemovals.remove(removalKey(code, playerId)) == null) {
            return;
        }
        gameRegistry.find(code).ifPresent(room -> removePlayerAndBroadcast(room, playerId));
    }

    private void schedulePromptExpiration(String code, Instant deadline) {
        ScheduledFuture<?> future = promptDeadlineScheduler.schedule(
                () -> runPromptExpiration(code, deadline),
                deadline);
        ScheduledFuture<?> previous = pendingPromptExpirations.put(code, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void runPromptExpiration(String code, Instant deadline) {
        if (pendingPromptExpirations.remove(code) == null) {
            return;
        }
        gameRegistry.find(code)
                .map(room -> withLockedRoom(code, lockedRoom -> {
                    if (lockedRoom.isPromptExpirationStale(deadline)) {
                        return null;
                    }
                    Map<String, String> autoSubmittedPrompts = lockedRoom.autoSubmitPrompts(deadline);
                    return new PromptExpirationResult(
                            PromptSubmissionSnapshot.from(lockedRoom),
                            autoSubmittedPrompts
                    );
                }))
                .ifPresent(result -> {
                    broadcastPromptSubmissionSnapshot(code, result.snapshot());
                    result.autoSubmittedPrompts()
                            .forEach((playerId, prompt) -> startImageGeneration(code, playerId, prompt));
                });
    }

    private void cancelPromptExpiration(String code) {
        ScheduledFuture<?> future = pendingPromptExpirations.remove(code);
        if (future != null) {
            future.cancel(false);
        }
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
        failImageGeneration(code, playerId, submittedPrompt);
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

    private void failImageGeneration(String code, String playerId, String submittedPrompt) {
        updateImageGenerationResult(
                code,
                playerId,
                room -> room.failImageGeneration(playerId),
                PromptEntryStatus.FAILED,
                submittedPrompt,
                null);
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
            String imageUrl) {
        try {
            boolean shouldSchedulePlayingTransition = gameRegistry.find(code)
                    .map(room -> withLockedRoom(code, lockedRoom -> {
                        boolean wasAllImagesGenerated = lockedRoom.hasAllImagesGenerated();
                        operation.accept(lockedRoom);

                        sendImageGenerationResult(
                                playerId,
                                new ImageGenerationResult(code, status, submittedPrompt, imageUrl)
                        );
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
        ScheduledFuture<?> future = imageGenerationCompletionScheduler.schedule(
                () -> runPlayingTransition(code),
                Instant.now().plus(imageGenerationCompletionDelay));
        ScheduledFuture<?> existing = pendingPlayingTransitions.putIfAbsent(code, future);
        if (existing != null) {
            future.cancel(false);
        }
    }

    private void runPlayingTransition(String code) {
        if (pendingPlayingTransitions.remove(code) == null) {
            return;
        }
        try {
            gameRegistry.find(code)
                    .ifPresent(room -> withLockedRoom(code, lockedRoom -> {
                        lockedRoom.advanceToPlaying();
                    }));
        } catch (RoomNotFoundException | ImagesNotReadyException ignored) {
            log.debug("이미지 생성 완료 전환 조건이 충족되지 않아 무시한다. roomCode={}", code);
        }
    }

    private static String removalKey(String code, String playerId) {
        return code + "::" + playerId;
    }

    private void removePlayerAndBroadcast(GameRoom room, String playerId) {
        boolean shouldSchedulePlayingTransition;
        synchronized (room) {
            boolean wasAllImagesGenerated = room.hasAllImagesGenerated();
            if (!room.removePlayer(playerId)) {
                return;
            }
            if (room.isEmpty()) {
                cancelPromptExpiration(room.getCode());
                cancelPlayingTransition(room.getCode());
                gameRegistry.remove(room.getCode());
                return;
            }
            if (!room.hasWaitingPrompt()) {
                cancelPromptExpiration(room.getCode());
            }
            broadcastLobbySnapshot(room.getCode(), LobbySnapshot.from(room));
            shouldSchedulePlayingTransition = !wasAllImagesGenerated && room.hasAllImagesGenerated();
        }
        if (shouldSchedulePlayingTransition) {
            schedulePlayingTransition(room.getCode());
        }
    }

    private record PromptExpirationResult(
            PromptSubmissionSnapshot snapshot,
            Map<String, String> autoSubmittedPrompts
    ) {
    }

    private void broadcastLobbySnapshot(String code, LobbySnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.lobbySnapshot(snapshot));
    }

    private void broadcastPromptSubmissionSnapshot(String code, PromptSubmissionSnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.promptSubmissionSnapshot(snapshot));
    }

    private void sendImageGenerationResult(String playerId, ImageGenerationResult result) {
        messagingTemplate.convertAndSendToUser(playerId, IMAGE_GENERATION_QUEUE, result);
    }

    private void cancelPlayingTransition(String code) {
        ScheduledFuture<?> future = pendingPlayingTransitions.remove(code);
        if (future != null) {
            future.cancel(false);
        }
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

    private GameRoom createRoomWithUniqueCode(Player host) {
        for (int attempt = 0; attempt < MAX_ROOM_CODE_ATTEMPTS; attempt++) {
            GameRoom room = GameRoom.create(roomCodeGenerator.generate(), host);
            if (gameRegistry.saveIfAbsent(room)) {
                return room;
            }
        }
        throw new RoomCodeGenerationFailedException();
    }
}
