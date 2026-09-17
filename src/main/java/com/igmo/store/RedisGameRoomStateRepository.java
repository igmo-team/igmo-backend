package com.igmo.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RedisGameRoomStateRepository {

    private static final String KEY_PREFIX = "igmo:game-room:";
    private static final String ROOM_CODE_FIELD = "roomCode";
    private static final String PHASE_FIELD = "phase";
    private static final String CURRENT_ROUND_FIELD = "currentRound";
    private static final String PLAYERS_FIELD = "players";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisGameRoomStateRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(GameRoom room) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ROOM_CODE_FIELD, room.getCode());
        fields.put(PHASE_FIELD, room.getPhase().name());
        fields.put(CURRENT_ROUND_FIELD, String.valueOf(currentRound(room)));
        fields.put(PLAYERS_FIELD, serializePlayers(room.getPlayers()));

        redisTemplate.opsForHash().putAll(key(room.getCode()), fields);
    }

    public Optional<RedisGameRoomState> find(String roomCode) {
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(key(roomCode));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(fields));
    }

    public void delete(String roomCode) {
        redisTemplate.delete(key(roomCode));
    }

    private RedisGameRoomState deserialize(Map<Object, Object> fields) {
        try {
            return new RedisGameRoomState(
                    field(fields, ROOM_CODE_FIELD),
                    GamePhase.valueOf(field(fields, PHASE_FIELD)),
                    Integer.parseInt(field(fields, CURRENT_ROUND_FIELD)),
                    objectMapper.readValue(
                            field(fields, PLAYERS_FIELD),
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class,
                                    RedisGameRoomState.PlayerState.class
                            )
                    )
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Redis 게임 방 상태를 읽을 수 없습니다.", exception);
        }
    }

    private String serializePlayers(List<Player> players) {
        List<RedisGameRoomState.PlayerState> playerStates = players.stream()
                .map(player -> new RedisGameRoomState.PlayerState(
                        player.getId(),
                        player.getNickname().value(),
                        player.getScore(),
                        player.isReady()
                ))
                .toList();
        try {
            return objectMapper.writeValueAsString(playerStates);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Redis 게임 방 참가자 상태를 저장할 수 없습니다.", exception);
        }
    }

    private int currentRound(GameRoom room) {
        if (room.getCurrentRound() == null) {
            return 0;
        }
        return room.getCurrentRound().getRoundNumber();
    }

    private String field(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new IllegalStateException("Redis 게임 방 상태 필드가 없습니다: " + name);
        }
        return value.toString();
    }

    private String key(String roomCode) {
        return KEY_PREFIX + roomCode;
    }
}
