package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.LobbySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class PlayerPresenceService {

    private final GameRoomRepository gameRoomRepository;
    private final GamePhaseScheduler gamePhaseScheduler;
    private final GameEventPublisher eventPublisher;
    private final TaskScheduler disconnectGraceScheduler;
    private final Map<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();

    @Value("${igmo.game.disconnect-grace}")
    private Duration disconnectGrace;

    public PlayerPresenceService(
            GameRoomRepository gameRoomRepository,
            GamePhaseScheduler gamePhaseScheduler,
            GameEventPublisher eventPublisher,
            @Qualifier("disconnectGraceScheduler") TaskScheduler disconnectGraceScheduler
    ) {
        this.gameRoomRepository = gameRoomRepository;
        this.gamePhaseScheduler = gamePhaseScheduler;
        this.eventPublisher = eventPublisher;
        this.disconnectGraceScheduler = disconnectGraceScheduler;
    }

    public void leaveGame(String code, String playerId, String secret) {
        gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            if (!room.isSecretValid(playerId, secret)) {
                throw new UnauthorizedPlayerException();
            }
            cancelPendingRemoval(code, playerId);
            removePlayer(code, room, playerId);
            return null;
        });
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
        if (pendingRemovals.remove(removalKey(code, playerId)) == null) {
            return;
        }
        gameRoomRepository.updateIfPresent(code, room -> {
            removePlayer(code, room, playerId);
            return null;
        });
    }

    private void removePlayer(String code, GameRoom room, String playerId) {
        if (!room.removePlayer(playerId)) {
            return;
        }
        if (room.isEmpty()) {
            gamePhaseScheduler.cancelAll(code);
            gameRoomRepository.remove(code);
            return;
        }
        if (room.getPhase() == GamePhase.LOBBY) {
            eventPublisher.publishLobby(code, LobbySnapshot.from(room));
        }
        // 인게임 퇴장에 따른 라운드 재조정과 스냅샷 발행은 #72에서 처리한다.
    }

    private static String removalKey(String code, String playerId) {
        return code + "::" + playerId;
    }
}
