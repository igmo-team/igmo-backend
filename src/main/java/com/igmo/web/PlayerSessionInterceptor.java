package com.igmo.web;

import java.util.Map;

import com.igmo.store.GameRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// STOMP CONNECT 헤더의 방/플레이어 식별 정보를 세션에 저장해 연결 끊김 시 퇴장 처리에 사용한다.
@Component
@RequiredArgsConstructor
public class PlayerSessionInterceptor implements ChannelInterceptor {

    public static final String ROOM_CODE_ATTRIBUTE = "roomCode";
    public static final String PLAYER_ID_ATTRIBUTE = "playerId";
    public static final String SECRET_HEADER = "secret";

    private final GameRegistry gameRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaderAccessor mutableAccessor = MessageHeaderAccessor.getMutableAccessor(message);
        if (!(mutableAccessor instanceof StompHeaderAccessor accessor)) {
            return message;
        }
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            bindPlayerToSession(accessor);
        }
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private void bindPlayerToSession(StompHeaderAccessor accessor) {
        String roomCode = accessor.getFirstNativeHeader(ROOM_CODE_ATTRIBUTE);
        String playerId = accessor.getFirstNativeHeader(PLAYER_ID_ATTRIBUTE);
        String secret = accessor.getFirstNativeHeader(SECRET_HEADER);
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null
                || !StringUtils.hasText(roomCode)
                || !StringUtils.hasText(playerId)
                || !StringUtils.hasText(secret)) {
            return;
        }
        // secret이 일치하지 않으면 위조 연결이므로 세션에 바인딩하지 않는다. (연결 끊김 시 퇴장 대상이 되지 않음)
        boolean validSecret = gameRegistry.find(roomCode)
                .map(room -> room.isSecretValid(playerId, secret))
                .orElse(false);
        if (!validSecret) {
            return;
        }
        sessionAttributes.put(ROOM_CODE_ATTRIBUTE, roomCode);
        sessionAttributes.put(PLAYER_ID_ATTRIBUTE, playerId);
        accessor.setUser(() -> playerId);
    }
}
