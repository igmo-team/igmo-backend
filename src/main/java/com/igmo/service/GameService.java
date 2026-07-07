package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class GameService {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";
    private static final int MAX_ROOM_CODE_ATTEMPTS = 10;

    private final GameRegistry gameRegistry;
    private final RoomCodeGenerator roomCodeGenerator;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler disconnectGraceScheduler;
    private final TaskScheduler promptDeadlineScheduler;

    @Value("${igmo.game.disconnect-grace}")
    private Duration disconnectGrace;
    @Value("${igmo.game.prompt-duration}")
    private Duration promptDuration;

    private final Map<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingPromptExpirations = new ConcurrentHashMap<>();

    public GameService(GameRegistry gameRegistry,
                       RoomCodeGenerator roomCodeGenerator,
                       SimpMessagingTemplate messagingTemplate,
                       @Qualifier("disconnectGraceScheduler") TaskScheduler disconnectGraceScheduler,
                       @Qualifier("promptDeadlineScheduler") TaskScheduler promptDeadlineScheduler) {
        this.gameRegistry = gameRegistry;
        this.roomCodeGenerator = roomCodeGenerator;
        this.messagingTemplate = messagingTemplate;
        this.disconnectGraceScheduler = disconnectGraceScheduler;
        this.promptDeadlineScheduler = promptDeadlineScheduler;
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
            room.submitPrompt(playerId, prompt, Instant.now());
            if (!room.hasWaitingPrompt()) {
                cancelPromptExpiration(code);
            }
            return PromptSubmissionSnapshot.from(room);
        });
        broadcastPromptSubmissionSnapshot(code, snapshot);
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
                    lockedRoom.expireWaitingPrompts(Instant.now());
                    return PromptSubmissionSnapshot.from(lockedRoom);
                }))
                .ifPresent(snapshot -> broadcastPromptSubmissionSnapshot(code, snapshot));
    }

    private void cancelPromptExpiration(String code) {
        ScheduledFuture<?> future = pendingPromptExpirations.remove(code);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String removalKey(String code, String playerId) {
        return code + "::" + playerId;
    }

    private void removePlayerAndBroadcast(GameRoom room, String playerId) {
        if (!room.removePlayer(playerId)) {
            return;
        }
        if (room.isEmpty()) {
            cancelPromptExpiration(room.getCode());
            gameRegistry.remove(room.getCode());
            return;
        }
        broadcastLobbySnapshot(room.getCode(), LobbySnapshot.from(room));
    }

    private void broadcastLobbySnapshot(String code, LobbySnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.lobbySnapshot(snapshot));
    }

    private void broadcastPromptSubmissionSnapshot(String code, PromptSubmissionSnapshot snapshot) {
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, RoomMessage.promptSubmissionSnapshot(snapshot));
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
