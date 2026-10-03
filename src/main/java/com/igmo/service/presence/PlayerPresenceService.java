package com.igmo.service.presence;

import com.igmo.domain.GameRoom;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.GameRoomStateSyncService;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.service.phase.GamePhaseService;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final GameRoomStateSyncService gameRoomStateSyncService;
    private final TaskScheduler disconnectGraceScheduler;
    private final PlayerSessionRegistry playerSessionRegistry;
    private final Map<PlayerKey, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();

    @Value("${igmo.game.disconnect-grace}")
    private Duration disconnectGrace;

    public PlayerPresenceService(
            GameRoomRepository gameRoomRepository,
            GamePhaseScheduler gamePhaseScheduler,
            GamePhaseService gamePhaseService,
            GameRoomStateSyncService gameRoomStateSyncService,
            @Qualifier("disconnectGraceScheduler") TaskScheduler disconnectGraceScheduler,
            PlayerSessionRegistry playerSessionRegistry
    ) {
        this.gameRoomRepository = gameRoomRepository;
        this.gamePhaseScheduler = gamePhaseScheduler;
        this.gamePhaseService = gamePhaseService;
        this.gameRoomStateSyncService = gameRoomStateSyncService;
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
            RemovalOutcome outcome = gameRoomRepository.updateWithCas(code, room -> {
                if (!room.hasPlayer(playerId)) {
                    throw new PlayerNotFoundException();
                }
                if (!room.isSecretValid(playerId, secret)) {
                    throw new UnauthorizedPlayerException();
                }
                RemovalOutcome result = removePlayer(room, playerId);
                if (result.room().isEmpty()) {
                    return RoomUpdate.deleted(result, List.of());
                }
                return RoomUpdate.changed(
                        result,
                        List.of(GameRoomDelivery.roomSnapshot()));
            }).value();
            completeRemoval(code, playerKey, outcome);
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
            gameRoomRepository.updateIfPresentWithCas(playerKey.roomCode(), room -> {
                        RemovalOutcome outcome = removePlayer(room, playerKey.playerId());
                        if (!outcome.removed()) {
                            return RoomUpdate.unchanged(null);
                        }
                        if (outcome.room().isEmpty()) {
                            return RoomUpdate.deleted(outcome, List.of());
                        }
                        return RoomUpdate.changed(
                                outcome,
                                List.of(GameRoomDelivery.roomSnapshot()));
                    }).filter(roomUpdate -> roomUpdate.changed())
                    .map(RoomUpdate::value)
                    .ifPresent(outcome -> completeRemoval(playerKey.roomCode(), playerKey, outcome));
        });
    }

    private RemovalOutcome removePlayer(GameRoom room, String playerId) {
        if (!room.removePlayer(playerId)) {
            return new RemovalOutcome(room, false);
        }
        return new RemovalOutcome(room, true);
    }

    private void completeRemoval(String code, PlayerKey playerKey, RemovalOutcome outcome) {
        if (!outcome.removed()) {
            return;
        }
        cancelPendingRemoval(playerKey);
        playerSessionRegistry.clear(playerKey);
        if (outcome.room().isEmpty()) {
            gamePhaseScheduler.cancelAll(code);
            return;
        }
        if (!outcome.room().isInLobby()) {
            gamePhaseService.onPlayerRemoved(code);
        }
        gameRoomStateSyncService.publishRoomSnapshot(outcome.room());
    }

    private record RemovalOutcome(GameRoom room, boolean removed) {
    }
}
