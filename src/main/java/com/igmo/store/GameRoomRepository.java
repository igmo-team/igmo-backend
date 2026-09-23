package com.igmo.store;

import com.igmo.domain.GameRoom;
import com.igmo.service.GameRoomRestoredEvent;
import com.igmo.service.exception.RoomNotFoundException;
import java.time.Instant;
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
            publishStateChanged(room.getCode());
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
            removeAttached(room);
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
        if (redisStateRepository.isEmpty()) {
            return;
        }

        Optional<GameRoom> restored = redisStateRepository.orElseThrow().restore(code);
        if (restored.isEmpty()) {
            gameRegistry.remove(code);
            return;
        }

        GameRoom room = restored.get();
        if (room.isLobbyExpired(Instant.now())) {
            gameRegistry.remove(code);
            return;
        }
        boolean wasAbsent = gameRegistry.find(code).isEmpty();
        gameRegistry.replace(room);
        if (wasAbsent) {
            eventPublisher.publishEvent(new GameRoomRestoredEvent(room));
        }
    }

    private Optional<GameRoom> restoreRoomByLocal(String code) {
        Optional<GameRoom> existing = gameRegistry.find(code);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        GameRoom room = existing.get();
        synchronized (room) {
            if (room.isLobbyExpired(Instant.now())) {
                removeAttached(room);
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
            deleteFromRedisAndPublish(code);
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
            removeAttached(room);
            return true;
        }
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
            persist(code, room);
            return result;
        }
    }

    private GameRoom findForUpdate(String code) {
        return gameRegistry.find(code)
                .orElseGet(() -> restore(code).orElseThrow(RoomNotFoundException::new));
    }

    public <T> Optional<T> updateIfPresent(String code, Function<GameRoom, T> operation) {
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
            T result = operation.apply(room);
            persist(code, room);
            return Optional.ofNullable(result);
        }
    }

    private void persist(String code, GameRoom room) {
        redisStateRepository.ifPresent(repository -> {
            if (isDetached(code, room)) {
                return;
            }
            room.incrementVersion();
            repository.save(room);
            publishStateChanged(code);
        });
    }

    private boolean isDetached(String code, GameRoom room) {
        return gameRegistry.find(code).orElse(null) != room;
    }

    private boolean isDetached(GameRoom room) {
        return isDetached(room.getCode(), room);
    }

    private void removeAttached(GameRoom room) {
        try {
            deleteFromRedisAndPublish(room.getCode());
        } finally {
            gameRegistry.removeIfSame(room);
        }
    }

    private void deleteFromRedisAndPublish(String code) {
        redisStateRepository.ifPresent(repository -> {
            repository.delete(code);
            publishStateChanged(code);
        });
    }

    private void publishStateChanged(String code) {
        stateChangePublisher.ifPresent(publisher -> publisher.publish(code));
    }
}
