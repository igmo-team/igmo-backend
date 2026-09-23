package com.igmo.store.redis;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.store.GameRoomRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

class GameRoomStateChangeSubscriberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("다른 인스턴스의 상태 변경 이벤트를 받으면 Redis 동기화를 요청한다.")
    void onMessage_다른인스턴스이벤트면_동기화한다() throws Exception {
        // given
        GameRoomStateChangePublisher publisher = org.mockito.Mockito.mock(GameRoomStateChangePublisher.class);
        GameRoomRepository repository = org.mockito.Mockito.mock(GameRoomRepository.class);
        when(publisher.isPublishedByThisInstance("remote")).thenReturn(false);
        GameRoomStateChangeSubscriber subscriber = new GameRoomStateChangeSubscriber(
                objectMapper,
                publisher,
                repository
        );
        String payload = objectMapper.writeValueAsString(
                new GameRoomStateChangedMessage("ABCD", "remote")
        );

        // when
        subscriber.onMessage(
                new DefaultMessage(new byte[0], payload.getBytes(StandardCharsets.UTF_8)),
                null
        );

        // then
        verify(repository).synchronizeFromRedis("ABCD");
    }

    @Test
    @DisplayName("자신이 발행한 상태 변경 이벤트는 다시 동기화하지 않는다.")
    void onMessage_자기인스턴스이벤트면_무시한다() throws Exception {
        // given
        GameRoomStateChangePublisher publisher = org.mockito.Mockito.mock(GameRoomStateChangePublisher.class);
        GameRoomRepository repository = org.mockito.Mockito.mock(GameRoomRepository.class);
        when(publisher.isPublishedByThisInstance("local")).thenReturn(true);
        GameRoomStateChangeSubscriber subscriber = new GameRoomStateChangeSubscriber(
                objectMapper,
                publisher,
                repository
        );
        String payload = objectMapper.writeValueAsString(
                new GameRoomStateChangedMessage("ABCD", "local")
        );

        // when
        subscriber.onMessage(
                new DefaultMessage(new byte[0], payload.getBytes(StandardCharsets.UTF_8)),
                null
        );

        // then
        verify(repository, never()).synchronizeFromRedis("ABCD");
    }
}
