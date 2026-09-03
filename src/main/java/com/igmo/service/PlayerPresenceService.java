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
    private final GamePhaseService gamePhaseService;
    private final GameEventPublisher eventPublisher;
    private final TaskScheduler disconnectGraceScheduler;
    private final PlayerSessionRegistry playerSessionRegistry;
    private final Map<PlayerKey, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();

    @Value("${igmo.game.disconnect-grace}")
    private Duration disconnectGrace;

    public PlayerPresenceService(
            GameRoomRepository gameRoomRepository,
            GamePhaseScheduler gamePhaseScheduler,
            GamePhaseService gamePhaseService,
            GameEventPublisher eventPublisher,
            @Qualifier("disconnectGraceScheduler") TaskScheduler disconnectGraceScheduler,
            PlayerSessionRegistry playerSessionRegistry
    ) {
        this.gameRoomRepository = gameRoomRepository;
        this.gamePhaseScheduler = gamePhaseScheduler;
        this.gamePhaseService = gamePhaseService;
        this.eventPublisher = eventPublisher;
        this.disconnectGraceScheduler = disconnectGraceScheduler;
        this.playerSessionRegistry = playerSessionRegistry;
    }

    public void handleConnect(String code, String playerId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PlayerKey playerKey = new PlayerKey(code, playerId);
        playerSessionRegistry.withPlayerLock(playerKey, () -> {
            playerSessionRegistry.register(playerKey, sessionId);
            cancelPendingRemoval(playerKey);
        });
    }

    public void leaveGame(String code, String playerId, String secret) {
        PlayerKey playerKey = new PlayerKey(code, playerId);
        playerSessionRegistry.withPlayerLock(playerKey, () -> {
            gameRoomRepository.update(code, room -> {
                if (!room.hasPlayer(playerId)) {
                    throw new PlayerNotFoundException();
                }
                if (!room.isSecretValid(playerId, secret)) {
                    throw new UnauthorizedPlayerException();
                }
                cancelPendingRemoval(playerKey);
                removePlayer(code, room, playerId);
                return null;
            });
        });
    }

    public void handleDisconnect(String code, String playerId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PlayerKey playerKey = new PlayerKey(code, playerId);
        playerSessionRegistry.withPlayerLock(playerKey, () -> {
            if (playerSessionRegistry.unregister(playerKey, sessionId)) {
                scheduleRemoval(playerKey);
            }
        });
    }

    public void cancelPendingRemoval(String code, String playerId) {
        PlayerKey playerKey = new PlayerKey(code, playerId);
        playerSessionRegistry.withPlayerLock(playerKey, () -> cancelPendingRemoval(playerKey));
    }

    private void scheduleRemoval(PlayerKey playerKey) {
        ScheduledFuture<?> future = disconnectGraceScheduler.schedule(
                () -> runScheduledRemoval(playerKey),
                Instant.now().plus(disconnectGrace));
        ScheduledFuture<?> previous = pendingRemovals.put(playerKey, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelPendingRemoval(PlayerKey playerKey) {
        ScheduledFuture<?> future = pendingRemovals.remove(playerKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void runScheduledRemoval(PlayerKey playerKey) {
        playerSessionRegistry.withPlayerLock(playerKey, () -> {
            if (pendingRemovals.remove(playerKey) == null
                    || playerSessionRegistry.hasActiveSession(playerKey)) {
                return;
            }
            gameRoomRepository.updateIfPresent(playerKey.roomCode(), room -> {
                removePlayer(playerKey.roomCode(), room, playerKey.playerId());
                return null;
            });
        });
    }

    private void removePlayer(String code, GameRoom room, String playerId) {
        if (!room.removePlayer(playerId)) {
            return;
        }
        playerSessionRegistry.clear(new PlayerKey(code, playerId));
        if (room.isEmpty()) {
            gamePhaseScheduler.cancelAll(code);
            gameRoomRepository.remove(code);
            return;
        }
        if (room.getPhase() == GamePhase.LOBBY) {
            eventPublisher.publishLobby(code, LobbySnapshot.from(room));
            return;
        }
        gamePhaseService.onPlayerRemoved(code);
        // 인게임 퇴장에 따른 라운드 재조정과 스냅샷 발행은 #72에서 처리한다.
    }
}
