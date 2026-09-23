package com.igmo.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.monitoring.GameMetrics;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisGameRoomStateRepository {

    private static final String KEY_PREFIX = "igmo:game-room:";
    private static final Duration ROOM_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GameMetrics gameMetrics;

    public void save(GameRoom room) {
        long startedAt = System.nanoTime();
        try {
            saveKeepingTtl(room);
            recordOperation("save", "success", startedAt);
        } catch (JsonProcessingException exception) {
            recordOperation("save", "error", startedAt);
            throw new IllegalStateException("Redis 게임 방 상태를 저장할 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            recordOperation("save", "error", startedAt);
            throw exception;
        }
    }

    public boolean saveIfAbsent(GameRoom room) {
        long startedAt = System.nanoTime();
        try {
            boolean saved = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(
                            key(room.getCode()),
                            serialize(room),
                            ROOM_TTL
                    )
            );
            recordOperation("save_if_absent", saved ? "success" : "conflict", startedAt);
            return saved;
        } catch (JsonProcessingException exception) {
            recordOperation("save_if_absent", "error", startedAt);
            throw new IllegalStateException("Redis 게임 방 상태를 저장할 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            recordOperation("save_if_absent", "error", startedAt);
            throw exception;
        }
    }

    public Optional<GameRoomState> find(String roomCode) {
        long startedAt = System.nanoTime();
        try {
            Optional<GameRoomState> state = readState(roomCode);
            recordOperation("find", state.isPresent() ? "success" : "miss", startedAt);
            return state;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            recordOperation("find", "error", startedAt);
            throw new IllegalStateException("Redis 게임 방 상태를 읽을 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            recordOperation("find", "error", startedAt);
            throw exception;
        }
    }

    public Optional<GameRoom> restore(String roomCode) {
        long startedAt = System.nanoTime();
        try {
            Optional<GameRoomState> state = readState(roomCode);
            if (state.isEmpty()) {
                recordOperation("restore", "miss", startedAt);
                return Optional.empty();
            }
            Optional<GameRoom> restored = state.map(GameRoom::restore);
            recordOperation("restore", "success", startedAt);
            return restored;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            recordOperation("restore", "error", startedAt);
            throw new IllegalStateException("Redis 게임 방 상태를 읽을 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            recordOperation("restore", "error", startedAt);
            throw exception;
        }
    }

    public void delete(String roomCode) {
        long startedAt = System.nanoTime();
        try {
            redisTemplate.delete(key(roomCode));
            recordOperation("delete", "success", startedAt);
        } catch (RuntimeException exception) {
            recordOperation("delete", "error", startedAt);
            throw exception;
        }
    }

    private String key(String roomCode) {
        return KEY_PREFIX + roomCode;
    }

    private void saveKeepingTtl(GameRoom room) throws JsonProcessingException {
        String redisKey = key(room.getCode());
        String serializedState = serialize(room);
        Boolean updated = redisTemplate.execute(
                (RedisCallback<Boolean>) connection -> connection.set(
                        redisTemplate.getStringSerializer().serialize(redisKey),
                        redisTemplate.getStringSerializer().serialize(serializedState),
                        Expiration.keepTtl(),
                        RedisStringCommands.SetOption.ifPresent()
                )
        );
        if (!Boolean.TRUE.equals(updated)) {
            throw new IllegalStateException("Redis 게임 방 상태를 갱신할 수 없습니다.");
        }
    }

    private Optional<GameRoomState> readState(String roomCode) throws JsonProcessingException {
        String serializedState = redisTemplate.opsForValue().get(key(roomCode));
        if (serializedState == null) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(serializedState, GameRoomState.class));
    }

    private void recordOperation(String operation, String outcome, long startedAt) {
        gameMetrics.recordRedisOperation(
                operation,
                outcome,
                Duration.ofNanos(System.nanoTime() - startedAt)
        );
    }

    private String serialize(GameRoom room) throws JsonProcessingException {
        return objectMapper.writeValueAsString(GameRoomState.from(room));
    }
}
