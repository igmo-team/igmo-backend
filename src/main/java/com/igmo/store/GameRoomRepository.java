package com.igmo.store;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.service.GameRoomRestoredEvent;
import com.igmo.service.LobbyExpiredEvent;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.redis.GameRoomStateChangePublisher;
import com.igmo.store.redis.RedisGameRoomStateRepository;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class GameRoomRepository {

    private final GameRegistry gameRegistry;
    private final Optional<RedisGameRoomStateRepository> redisStateRepository;
    private final Optional<GameRoomStateChangePublisher> stateChangePublisher;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository,
            Optional<GameRoomStateChangePublisher> stateChangePublisher,
            ApplicationEventPublisher eventPublisher
    ) {
        this.gameRegistry = gameRegistry;
        this.redisStateRepository = redisStateRepository;
        this.stateChangePublisher = stateChangePublisher;
        this.eventPublisher = eventPublisher;
    }

    public GameRoomRepository(GameRegistry gameRegistry) {
        this(gameRegistry, Optional.empty(), Optional.empty(), event -> {
        });
    }

    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository
    ) {
        this(gameRegistry, redisStateRepository, Optional.empty(), event -> {
        });
    }

    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this(gameRegistry, redisStateRepository, Optional.empty(), eventPublisher);
    }

    public boolean saveIfAbsent(GameRoom room) {
        boolean saved = gameRegistry.saveIfAbsent(room);
        if (!saved) {
            return false;
        }
        if (redisStateRepository.isEmpty()) {
            return true;
        }

        boolean persisted;
        try {
            room.incrementVersion();
            persisted = redisStateRepository.orElseThrow().saveIfAbsent(room);
        } catch (RuntimeException exception) {
            gameRegistry.removeIfSame(room);
            throw exception;
        }
        if (persisted) {
            publishStateChanged(room.getCode(), room.getVersion(), List.of());
            return true;
        }
        gameRegistry.removeIfSame(room);
        return false;
    }

    public boolean remove(GameRoom room) {
        synchronized (room) {
            if (isDetached(room)) {
                return false;
            }
            List<GameRoomDelivery> deliveries = room.getPhase() == GamePhase.ENDED
                    ? List.of(GameRoomDelivery.gameResult(GameResultSnapshot.from(room)))
                    : List.of(GameRoomDelivery.roomRemoved());
            removeAttached(room, deliveries);
            return true;
        }
    }

    public Optional<GameRoom> restore(String code) {
        Optional<GameRoom> localRoom = restoreRoomByLocal(code);
        if (localRoom.isPresent()) {
            return localRoom;
        }
        return restoreRoomByRedis(code);
    }

    public void synchronizeFromRedis(String code) {
        synchronizeFromRedisAndGet(code);
    }

    public Optional<GameRoom> synchronizeFromRedisAndGet(String code) {
        return synchronizeFromRedisAndGet(code, Long.MAX_VALUE);
    }

    public Optional<GameRoom> synchronizeFromRedisAndGet(String code, long eventVersion) {
        if (redisStateRepository.isEmpty()) {
            return gameRegistry.find(code);
        }

        Optional<GameRoom> restored = redisStateRepository.orElseThrow().restore(code);
        if (restored.isEmpty()) {
            return removeLocalRoomIfNotNewer(code, eventVersion);
        }

        GameRoom room = restored.get();
        if (room.isLobbyExpired(Instant.now())) {
            return removeLocalRoomIfNotNewer(code, eventVersion);
        }
        boolean wasAbsent = gameRegistry.find(code).isEmpty();
        GameRoom currentRoom;
        if (eventVersion == Long.MAX_VALUE) {
            gameRegistry.replace(room);
            currentRoom = room;
        } else {
            currentRoom = gameRegistry.replaceIfNewer(room);
        }
        if (wasAbsent) {
            eventPublisher.publishEvent(new GameRoomRestoredEvent(currentRoom));
        }
        return Optional.of(currentRoom);
    }

    private Optional<GameRoom> removeLocalRoomIfNotNewer(String code, long eventVersion) {
        Optional<GameRoom> localRoom = gameRegistry.find(code);
        if (localRoom.isEmpty()) {
            return Optional.empty();
        }
        GameRoom room = localRoom.get();
        if (room.getVersion() <= eventVersion && gameRegistry.removeIfSame(room)) {
            return Optional.empty();
        }
        return gameRegistry.find(code);
    }

    private Optional<GameRoom> restoreRoomByLocal(String code) {
        Optional<GameRoom> existing = gameRegistry.find(code);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        GameRoom room = existing.get();
        if (removeLobbyIfExpired(room, Instant.now())) {
            return Optional.empty();
        }
        synchronized (room) {
            if (isDetached(room)) {
                return Optional.empty();
            }
        }
        return Optional.of(room);
    }

    private Optional<GameRoom> restoreRoomByRedis(String code) {
        Optional<GameRoom> restored = redisStateRepository.flatMap(
                repository -> repository.restore(code)
        );
        if (restored.isEmpty()) {
            return Optional.empty();
        }

        GameRoom room = restored.get();
        if (room.isLobbyExpired(Instant.now())) {
            if (deleteFromRedisAndPublish(
                    code,
                    room.getVersion() + 1,
                    List.of(GameRoomDelivery.lobbyExpired()))) {
                eventPublisher.publishEvent(new LobbyExpiredEvent(code));
            }
            return Optional.empty();
        }
        return registerRestoredRoom(room);
    }

    private Optional<GameRoom> registerRestoredRoom(GameRoom room) {
        if (!gameRegistry.saveIfAbsent(room)) {
            return gameRegistry.find(room.getCode());
        }

        eventPublisher.publishEvent(new GameRoomRestoredEvent(room)); //복원 이벤트 발행
        return Optional.of(room);
    }

    public boolean removeLobbyIfExpired(GameRoom room, Instant now) {
        synchronized (room) {
            if (isDetached(room) || !room.isLobbyExpired(now)) {
                return false;
            }
            try {
                removeAttached(room, List.of(GameRoomDelivery.lobbyExpired()));
            } finally {
                eventPublisher.publishEvent(new LobbyExpiredEvent(room.getCode()));
            }
        }
        return true;
    }

    public <T> T update(String code, Function<GameRoom, T> operation) {
        GameRoom room = findForUpdate(code);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            if (removeLobbyIfExpired(room, Instant.now())) {
                throw new RoomNotFoundException();
            }
            T result = operation.apply(room);
            persist(code, room, List.of());
            return result;
        }
    }

    public <T> T updateWithDeliveries(String code, Function<GameRoom, RoomUpdate<T>> operation) {
        GameRoom room = findForUpdate(code);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            if (removeLobbyIfExpired(room, Instant.now())) {
                throw new RoomNotFoundException();
            }
            RoomUpdate<T> result = operation.apply(room);
            if (result.changed()) {
                persist(code, room, result.deliveries());
            }
            return result.value();
        }
    }

    private GameRoom findForUpdate(String code) {
        return gameRegistry.find(code)
                .orElseGet(() -> restore(code).orElseThrow(RoomNotFoundException::new));
    }

    public <T> Optional<T> updateIfPresent(String code, Function<GameRoom, RoomUpdate<T>> operation) {
        Optional<GameRoom> found = gameRegistry.find(code);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        GameRoom room = found.get();
        synchronized (room) {
            if (isDetached(code, room)) {
                return Optional.empty();
            }
            if (removeLobbyIfExpired(room, Instant.now())) {
                return Optional.empty();
            }
            RoomUpdate<T> result = operation.apply(room);
            if (result.changed()) {
                persist(code, room, result.deliveries());
            }
            return Optional.ofNullable(result.value());
        }
    }

    private void persist(String code, GameRoom room, List<GameRoomDelivery> deliveries) {
        redisStateRepository.ifPresent(repository -> {
            if (isDetached(code, room)) {
                return;
            }
            room.incrementVersion();
            repository.save(room);
            publishStateChanged(code, room.getVersion(), deliveries);
        });
    }

    private boolean isDetached(String code, GameRoom room) {
        return gameRegistry.find(code).orElse(null) != room;
    }

    private boolean isDetached(GameRoom room) {
        return isDetached(room.getCode(), room);
    }

    private void removeAttached(GameRoom room, List<GameRoomDelivery> deliveries) {
        try {
            deleteFromRedisAndPublish(room.getCode(), room.getVersion() + 1, deliveries);
        } finally {
            gameRegistry.removeIfSame(room);
        }
    }

    private boolean deleteFromRedisAndPublish(String code, long version, List<GameRoomDelivery> deliveries) {
        return redisStateRepository.map(repository -> {
            boolean deleted = repository.delete(code);
            publishStateChanged(code, version, deliveries);
            return deleted;
        }).orElse(false);
    }

    private void publishStateChanged(String code, long version, List<GameRoomDelivery> deliveries) {
        stateChangePublisher.ifPresent(publisher -> publisher.publish(code, version, deliveries));
    }
}
