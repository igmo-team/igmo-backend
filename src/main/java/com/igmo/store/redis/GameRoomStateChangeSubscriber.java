package com.igmo.store.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.store.GameRoomRepository;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class GameRoomStateChangeSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final GameRoomStateChangePublisher publisher;
    private final GameRoomRepository gameRoomRepository;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            GameRoomStateChangedMessage stateChanged = objectMapper.readValue(
                    message.getBody(),
                    GameRoomStateChangedMessage.class
            );
            if (publisher.isPublishedByThisInstance(stateChanged.sourceInstanceId())) {
                return;
            }
            gameRoomRepository.synchronizeFromRedis(stateChanged.roomCode());
        } catch (IOException exception) {
            log.warn("게임방 상태 변경 이벤트를 해석할 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            log.warn("게임방 상태를 Redis에서 동기화할 수 없습니다.", exception);
        }
    }
}
