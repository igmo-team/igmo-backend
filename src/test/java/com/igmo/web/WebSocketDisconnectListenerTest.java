package com.igmo.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.Map;

import com.igmo.service.GameService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class WebSocketDisconnectListenerTest {

    private final GameService gameService = mock(GameService.class);
    private final WebSocketDisconnectListener listener = new WebSocketDisconnectListener(gameService);

    @Test
    @DisplayName("세션에 방/플레이어 식별 정보가 있으면 연결 끊김 퇴장 처리를 호출한다.")
    void handleSessionDisconnect_식별_정보가_있으면_퇴장_처리를_호출한다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("roomCode", "ABCD");
        sessionAttributes.put("playerId", "player-1");
        SessionDisconnectEvent event = disconnectEvent(sessionAttributes);

        // when
        listener.handleSessionDisconnect(event);

        // then
        verify(gameService).handleDisconnect("ABCD", "player-1");
    }

    @Test
    @DisplayName("세션에 식별 정보가 없으면 퇴장 처리를 호출하지 않는다.")
    void handleSessionDisconnect_식별_정보가_없으면_퇴장_처리를_호출하지_않는다() {
        // given
        SessionDisconnectEvent event = disconnectEvent(new HashMap<>());

        // when
        listener.handleSessionDisconnect(event);

        // then
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("세션 attributes 자체가 없으면 퇴장 처리를 호출하지 않는다.")
    void handleSessionDisconnect_세션_attributes가_없으면_퇴장_처리를_호출하지_않는다() {
        // given
        SessionDisconnectEvent event = disconnectEvent(null);

        // when
        listener.handleSessionDisconnect(event);

        // then
        verifyNoInteractions(gameService);
    }

    private SessionDisconnectEvent disconnectEvent(Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(sessionAttributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, "session-1", CloseStatus.NORMAL);
    }
}
