package com.igmo.store;

import com.igmo.domain.GameRoom;
import com.igmo.service.exception.RoomNotFoundException;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class GameRoomRepository {

    private final GameRegistry gameRegistry;

    public GameRoomRepository(GameRegistry gameRegistry) {
        this.gameRegistry = gameRegistry;
    }

    public boolean saveIfAbsent(GameRoom room) {
        return gameRegistry.saveIfAbsent(room);
    }

    public void remove(String code) {
        gameRegistry.remove(code);
    }

    public <T> T update(String code, Function<GameRoom, T> operation) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            return operation.apply(room);
        }
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
            return Optional.ofNullable(operation.apply(room));
        }
    }

    private boolean isDetached(String code, GameRoom room) {
        return gameRegistry.find(code).orElse(null) != room;
    }
}
