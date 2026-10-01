package com.igmo.store.redis;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GameRoomStateSyncService;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
        GameRoomStateSyncService stateSyncService = org.mockito.Mockito.mock(GameRoomStateSyncService.class);
        GameEventPublisher eventPublisher = org.mockito.Mockito.mock(GameEventPublisher.class);
        when(publisher.isPublishedByThisInstance("remote")).thenReturn(false);
        when(repository.synchronizeFromRedisAndGet("ABCD", 5)).thenReturn(Optional.empty());
        GameRoomStateChangeSubscriber subscriber = new GameRoomStateChangeSubscriber(
                objectMapper,
                publisher,
                repository,
                stateSyncService,
                eventPublisher
        );
        String payload = objectMapper.writeValueAsString(
                new GameRoomStateChangedMessage("ABCD", "remote", 5, List.of())
        );

        // when
        subscriber.onMessage(
                new DefaultMessage(new byte[0], payload.getBytes(StandardCharsets.UTF_8)),
                null
        );

        // then
        verify(repository).synchronizeFromRedisAndGet("ABCD", 5);
    }

    @Test
    @DisplayName("자신이 발행한 상태 변경 이벤트는 다시 동기화하지 않는다.")
    void onMessage_자기인스턴스이벤트면_무시한다() throws Exception {
        // given
        GameRoomStateChangePublisher publisher = org.mockito.Mockito.mock(GameRoomStateChangePublisher.class);
        GameRoomRepository repository = org.mockito.Mockito.mock(GameRoomRepository.class);
        GameRoomStateSyncService stateSyncService = org.mockito.Mockito.mock(GameRoomStateSyncService.class);
        GameEventPublisher eventPublisher = org.mockito.Mockito.mock(GameEventPublisher.class);
        when(publisher.isPublishedByThisInstance("local")).thenReturn(true);
        GameRoomStateChangeSubscriber subscriber = new GameRoomStateChangeSubscriber(
                objectMapper,
                publisher,
                repository,
                stateSyncService,
                eventPublisher
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
        verify(repository, never()).synchronizeFromRedisAndGet("ABCD", 0);
    }

    @Test
    @DisplayName("삭제된 게임방의 최종 결과는 이벤트에 담긴 스냅샷으로 방송한다.")
    void onMessage_방이삭제된최종결과면_저장된스냅샷으로방송한다() throws Exception {
        // given
        GameRoomStateChangePublisher publisher = org.mockito.Mockito.mock(GameRoomStateChangePublisher.class);
        GameRoomRepository repository = org.mockito.Mockito.mock(GameRoomRepository.class);
        GameRoomStateSyncService stateSyncService = org.mockito.Mockito.mock(GameRoomStateSyncService.class);
        GameEventPublisher eventPublisher = org.mockito.Mockito.mock(GameEventPublisher.class);
        GameResultSnapshot snapshot = new GameResultSnapshot("ABCD", GamePhase.ENDED, List.of());
        when(publisher.isPublishedByThisInstance("remote")).thenReturn(false);
        when(repository.synchronizeFromRedisAndGet("ABCD", 9)).thenReturn(Optional.empty());
        GameRoomStateChangeSubscriber subscriber = new GameRoomStateChangeSubscriber(
                objectMapper,
                publisher,
                repository,
                stateSyncService,
                eventPublisher
        );
        String payload = objectMapper.writeValueAsString(new GameRoomStateChangedMessage(
                "ABCD",
                "remote",
                9,
                List.of(GameRoomDelivery.gameResult(snapshot))
        ));

        // when
        subscriber.onMessage(
                new DefaultMessage(new byte[0], payload.getBytes(StandardCharsets.UTF_8)),
                null
        );

        // then
        verify(eventPublisher).publish("ABCD", RoomMessage.gameResultSnapshot(snapshot));
    }
}
