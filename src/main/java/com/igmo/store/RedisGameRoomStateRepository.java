package com.igmo.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisGameRoomStateRepository {

    private static final String KEY_PREFIX = "igmo:game-room:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(GameRoom room) {
        try {
            String serializedState = objectMapper.writeValueAsString(GameRoomState.from(room));
            redisTemplate.opsForValue().set(key(room.getCode()), serializedState);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Redis 게임 방 상태를 저장할 수 없습니다.", exception);
        }
    }

    public Optional<GameRoomState> find(String roomCode) {
        String serializedState = redisTemplate.opsForValue().get(key(roomCode));
        if (serializedState == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(serializedState, GameRoomState.class));
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Redis 게임 방 상태를 읽을 수 없습니다.", exception);
        }
    }

    public Optional<GameRoom> restore(String roomCode) {
        return find(roomCode).map(GameRoom::restore);
    }

    public void delete(String roomCode) {
        redisTemplate.delete(key(roomCode));
    }

    private String key(String roomCode) {
        return KEY_PREFIX + roomCode;
    }
}
