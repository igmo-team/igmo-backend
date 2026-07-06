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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameService {

    private static final String LOBBY_TOPIC_PREFIX = "/topic/room/";
    private static final int MAX_ROOM_CODE_ATTEMPTS = 10;

    private final GameRegistry gameRegistry;
    private final RoomCodeGenerator roomCodeGenerator;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler webSocketHeartbeatScheduler;

    @Value("${igmo.game.disconnect-grace}")
    private Duration disconnectGrace;

    private final Map<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();

    public CreateGameResponse createGame(String nickname) {
        Player host = new Player(nickname);
        GameRoom room = createRoomWithUniqueCode(host);
        return new CreateGameResponse(room.getCode(), host.getId(), host.getSecret(), LobbySnapshot.from(room));
    }

    public JoinGameResponse joinGame(String code, String nickname) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            // 이 사이에 삭제된 경우
            if (gameRegistry.find(code).orElse(null) != room) {
                throw new RoomNotFoundException();
            }
            Player player = new Player(nickname);
            room.addPlayer(player);
            LobbySnapshot snapshot = LobbySnapshot.from(room);
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + code, snapshot);
            return new JoinGameResponse(player.getId(), player.getSecret(), snapshot);
        }
    }

    public void leaveGame(String code, String playerId, String secret) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            if (!room.isSecretValid(playerId, secret)) {
                throw new UnauthorizedPlayerException();
            }
            cancelPendingRemoval(code, playerId);
            removePlayerAndBroadcast(room, playerId);
        }
    }

    public void handleDisconnect(String code, String playerId) {
        ScheduledFuture<?> future = webSocketHeartbeatScheduler.schedule(
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

    private static String removalKey(String code, String playerId) {
        return code + "::" + playerId;
    }

    private void removePlayerAndBroadcast(GameRoom room, String playerId) {
        synchronized (room) {
            if (!room.removePlayer(playerId)) {
                return;
            }
            if (room.isEmpty()) {
                gameRegistry.remove(room.getCode());
                return;
            }
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + room.getCode(), LobbySnapshot.from(room));
        }
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
