package com.igmo.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.monitoring.GameMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisGameRoomStateRepository {

    private static final String KEY_PREFIX = "igmo:game-room:";
    private static final Duration ROOM_TTL = Duration.ofHours(1);
    private static final RedisScript<Long> COMPARE_AND_SET_SCRIPT = RedisScript.of(
            new ClassPathResource("redis/compare-and-set-game-room.lua"),
            Long.class
    );
    private static final RedisScript<Long> COMPARE_AND_DELETE_SCRIPT = RedisScript.of(
            new ClassPathResource("redis/compare-and-delete-game-room.lua"),
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GameMetrics gameMetrics;

    public enum ConditionalWriteResult {
        APPLIED,
        VERSION_MISMATCH,
        KEY_NOT_FOUND
    }

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

    public ConditionalWriteResult compareAndSet(GameRoom candidate, long expectedVersion) {
        validateCandidateVersion(candidate, expectedVersion);
        long startedAt = System.nanoTime();
        try {
            Long resultCode = redisTemplate.execute(
                    COMPARE_AND_SET_SCRIPT,
                    List.of(key(candidate.getCode())),
                    Long.toString(expectedVersion),
                    serialize(candidate)
            );
            ConditionalWriteResult result = conditionalWriteResult(resultCode);
            recordOperation("compare_and_set", metricOutcome(result), startedAt);
            return result;
        } catch (JsonProcessingException exception) {
            recordOperation("compare_and_set", "error", startedAt);
            throw new IllegalStateException("Redis 게임 방 상태를 저장할 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            recordOperation("compare_and_set", "error", startedAt);
            throw exception;
        }
    }

    public ConditionalWriteResult compareAndDelete(String roomCode, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("기준 버전은 0 이상이어야 합니다.");
        }
        long startedAt = System.nanoTime();
        try {
            Long resultCode = redisTemplate.execute(
                    COMPARE_AND_DELETE_SCRIPT,
                    List.of(key(roomCode)),
                    Long.toString(expectedVersion)
            );
            ConditionalWriteResult result = conditionalWriteResult(resultCode);
            recordOperation("compare_and_delete", metricOutcome(result), startedAt);
            return result;
        } catch (RuntimeException exception) {
            recordOperation("compare_and_delete", "error", startedAt);
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

    public boolean delete(String roomCode) {
        long startedAt = System.nanoTime();
        try {
            boolean deleted = Boolean.TRUE.equals(redisTemplate.delete(key(roomCode)));
            recordOperation("delete", "success", startedAt);
            return deleted;
        } catch (RuntimeException exception) {
            recordOperation("delete", "error", startedAt);
            throw exception;
        }
    }

    private String key(String roomCode) {
        return KEY_PREFIX + roomCode;
    }

    private void validateCandidateVersion(GameRoom candidate, long expectedVersion) {
        if (expectedVersion < 0
                || expectedVersion == Long.MAX_VALUE
                || candidate.getVersion() != expectedVersion + 1) {
            throw new IllegalArgumentException("후보 상태 버전은 기준 버전보다 1 커야 합니다.");
        }
    }

    private ConditionalWriteResult conditionalWriteResult(Long resultCode) {
        if (resultCode == null) {
            throw new IllegalStateException("Redis 조건부 저장 결과가 비어 있습니다.");
        }
        return switch (resultCode.intValue()) {
            case 1 -> ConditionalWriteResult.APPLIED;
            case 0 -> ConditionalWriteResult.VERSION_MISMATCH;
            case -1 -> ConditionalWriteResult.KEY_NOT_FOUND;
            default -> throw new IllegalStateException("Redis 조건부 저장 결과가 올바르지 않습니다.");
        };
    }

    private String metricOutcome(ConditionalWriteResult result) {
        return switch (result) {
            case APPLIED -> "success";
            case VERSION_MISMATCH -> "conflict";
            case KEY_NOT_FOUND -> "miss";
        };
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
