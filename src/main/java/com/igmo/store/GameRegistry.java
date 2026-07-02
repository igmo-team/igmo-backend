package com.igmo.store;

import com.igmo.domain.GameRoom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class GameRegistry {

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();

    public boolean saveIfAbsent(GameRoom room) {
        return rooms.putIfAbsent(room.getCode(), room) == null;
    }

    public Optional<GameRoom> find(String code) {
        return Optional.ofNullable(rooms.get(code));
    }
}
