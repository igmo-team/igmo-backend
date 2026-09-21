package com.igmo.store;

import com.igmo.domain.GameRoom;
import com.igmo.service.exception.RoomNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GameRoomRepository {

    private final GameRegistry gameRegistry;
    private final Optional<RedisGameRoomStateRepository> redisStateRepository;

    public GameRoomRepository(GameRegistry gameRegistry) {
        this(gameRegistry, Optional.empty());
    }

    @Autowired
    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository
    ) {
        this.gameRegistry = gameRegistry;
        this.redisStateRepository = redisStateRepository;
    }

    public boolean saveIfAbsent(GameRoom room) {
        boolean saved = gameRegistry.saveIfAbsent(room);
        if (!saved) {
            return false;
        }
        if (redisStateRepository.isEmpty()) {
            return true;
        }

        try {
            room.incrementVersion();
            boolean persisted = redisStateRepository.orElseThrow().saveIfAbsent(room);
            if (persisted) {
                return true;
            }
        } catch (RuntimeException exception) {
            gameRegistry.removeIfSame(room);
            throw exception;
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
        Optional<GameRoom> existing = gameRegistry.find(code);
        if (existing.isPresent()) {
            GameRoom room = existing.get();
            synchronized (room) {
                if (room.isLobbyExpired(Instant.now())) {
                    removeAttached(room);
                    return Optional.empty();
                }
            }
            return existing;
        }
        Optional<GameRoom> restored = redisStateRepository.flatMap(
                repository -> repository.restore(code)
        );
        if (restored.isEmpty()) {
            return Optional.empty();
        }
        GameRoom room = restored.get();
        if (room.isLobbyExpired(Instant.now())) {
            redisStateRepository.ifPresent(repository -> repository.delete(code));
            return Optional.empty();
        }
        if (gameRegistry.saveIfAbsent(room)) {
            return Optional.of(room);
        }
        return gameRegistry.find(code);
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
            redisStateRepository.ifPresent(repository -> repository.delete(room.getCode()));
        } finally {
            gameRegistry.removeIfSame(room);
        }
    }
}
