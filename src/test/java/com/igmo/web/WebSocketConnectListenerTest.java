package com.igmo.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.igmo.service.GameService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;

class WebSocketConnectListenerTest {

    private final GameService gameService = mock(GameService.class);
    private final WebSocketConnectListener listener = new WebSocketConnectListener(gameService);

    @Test
    @DisplayName("세션에 방/플레이어 식별 정보가 있으면 예약된 삭제를 취소한다.")
    void handleSessionConnect_식별_정보가_있으면_예약된_삭제를_취소한다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("roomCode", "ABCD");
        sessionAttributes.put("playerId", "player-1");
        SessionConnectEvent event = connectEvent(sessionAttributes);

        // when
        listener.handleSessionConnect(event);

        // then
        verify(gameService).cancelPendingRemoval("ABCD", "player-1");
    }

    @Test
    @DisplayName("세션에 식별 정보가 없거나 일부 누락되면 예약 취소를 호출하지 않는다.")
    void handleSessionConnect_식별_정보가_없거나_일부_누락되면_예약_취소를_호출하지_않는다() {
        // given
        Map<String, Object> roomCodeOnly = new HashMap<>();
        roomCodeOnly.put("roomCode", "ABCD");
        Map<String, Object> playerIdOnly = new HashMap<>();
        playerIdOnly.put("playerId", "player-1");

        // when
        listener.handleSessionConnect(connectEvent(new HashMap<>()));
        listener.handleSessionConnect(connectEvent(roomCodeOnly));
        listener.handleSessionConnect(connectEvent(playerIdOnly));

        // then
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("세션 attributes 자체가 없으면 예약 취소를 호출하지 않는다.")
    void handleSessionConnect_세션_attributes가_없으면_예약_취소를_호출하지_않는다() {
        // given
        SessionConnectEvent event = connectEvent(null);

        // when
        listener.handleSessionConnect(event);

        // then
        verifyNoInteractions(gameService);
    }

    private SessionConnectEvent connectEvent(Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(sessionAttributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message);
    }
}
