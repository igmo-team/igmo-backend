package com.igmo.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.igmo.monitoring.GameMetrics;
import com.igmo.web.dto.RoomMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class GameEventPublisherTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final GameEventPublisher eventPublisher = new GameEventPublisher(messagingTemplate, gameMetrics);

    @Test
    @DisplayName("방 브로드캐스트 성공 시 성공 카운터를 증가시킨다.")
    void publish_incrementsBroadcastCountOnSuccess() {
        // given
        RoomMessage<?> message = mock(RoomMessage.class);

        // when
        eventPublisher.publish("ABCD", message);

        // then
        verify(gameMetrics).incrementWebsocketBroadcastCount();
    }
}
