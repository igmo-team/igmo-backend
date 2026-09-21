package com.igmo.store;

import com.igmo.domain.GameRoom;
import java.util.List;
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

    public List<GameRoom> snapshot() {
        return List.copyOf(rooms.values());
    }

    public int count() {
        return rooms.size();
    }

    public boolean removeIfSame(GameRoom room) {
        return rooms.remove(room.getCode(), room);
    }
}
