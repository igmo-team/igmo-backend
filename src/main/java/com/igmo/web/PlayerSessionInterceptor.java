package com.igmo.web;

import java.util.Map;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// STOMP CONNECT 헤더의 방/플레이어 식별 정보를 세션에 저장해 연결 끊김 시 퇴장 처리에 사용한다.
@Component
public class PlayerSessionInterceptor implements ChannelInterceptor {

    public static final String ROOM_CODE_ATTRIBUTE = "roomCode";
    public static final String PLAYER_ID_ATTRIBUTE = "playerId";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            bindPlayerToSession(accessor);
        }
        return message;
    }

    private void bindPlayerToSession(StompHeaderAccessor accessor) {
        String roomCode = accessor.getFirstNativeHeader(ROOM_CODE_ATTRIBUTE);
        String playerId = accessor.getFirstNativeHeader(PLAYER_ID_ATTRIBUTE);
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null || !StringUtils.hasText(roomCode) || !StringUtils.hasText(playerId)) {
            return;
        }
        sessionAttributes.put(ROOM_CODE_ATTRIBUTE, roomCode);
        sessionAttributes.put(PLAYER_ID_ATTRIBUTE, playerId);
    }
}
