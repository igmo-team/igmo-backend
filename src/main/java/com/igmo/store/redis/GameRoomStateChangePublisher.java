package com.igmo.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.store.GameRoomDelivery;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class GameRoomStateChangePublisher {

    public static final String CHANNEL = "igmo:game-room:state-changed";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String instanceId = UUID.randomUUID().toString();

    public void publish(String roomCode, long roomVersion, List<GameRoomDelivery> deliveries) {
        try {
            String message = objectMapper.writeValueAsString(
                    new GameRoomStateChangedMessage(roomCode, instanceId, roomVersion, deliveries)
            );
            redisTemplate.convertAndSend(CHANNEL, message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("게임방 상태 변경 이벤트를 발행할 수 없습니다.", exception);
        }
    }

    public boolean isPublishedByThisInstance(String sourceInstanceId) {
        return instanceId.equals(sourceInstanceId);
    }
}
