package com.igmo.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.igmo.monitoring.GameMetrics;
import com.igmo.web.dto.OwnVoteOptionNotice;
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

    @Test
    @DisplayName("방 브로드캐스트 실패 시 실패 카운터를 증가시키고 예외를 전파한다.")
    void publish_incrementsBroadcastFailureAndRethrowsException() {
        // given
        RoomMessage<?> message = mock(RoomMessage.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(messagingTemplate)
                .convertAndSend("/topic/rooms/ABCD", message);

        // when & then
        assertThatThrownBy(() -> eventPublisher.publish("ABCD", message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        verify(gameMetrics).incrementWebsocketBroadcastFailure();
    }

    @Test
    @DisplayName("본인 투표 보기를 해당 플레이어의 개인큐로 전송한다.")
    void sendOwnVoteOption_개인큐로_본인_보기를_전송한다() {
        // given
        OwnVoteOptionNotice notice = new OwnVoteOptionNotice("ABCD", 1, false, "option-1");

        // when
        eventPublisher.sendOwnVoteOption("player-1", notice);

        // then
        verify(messagingTemplate).convertAndSendToUser("player-1", "/queue/vote-own-option", notice);
    }
}
