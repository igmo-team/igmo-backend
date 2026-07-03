package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.igmo.domain.GameRoom;
import com.igmo.store.GameRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class PlayerSessionInterceptorTest {

    private final GameRegistry gameRegistry = mock(GameRegistry.class);
    private final PlayerSessionInterceptor interceptor = new PlayerSessionInterceptor(gameRegistry);
    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    @DisplayName("secret이 유효한 CONNECT 프레임의 roomCode와 playerId 헤더를 세션 attributes에 저장한다.")
    void preSend_CONNECT_헤더를_세션에_저장한다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setNativeHeader("roomCode", "ABCD");
        accessor.setNativeHeader("playerId", "player-1");
        accessor.setNativeHeader("secret", "s3cr3t");
        GameRoom room = mock(GameRoom.class);
        given(room.isSecretValid("player-1", "s3cr3t")).willReturn(true);
        given(gameRegistry.find("ABCD")).willReturn(Optional.of(room));

        // when
        interceptor.preSend(toMessage(accessor), channel);

        // then
        assertThat(sessionAttributes)
                .containsEntry("roomCode", "ABCD")
                .containsEntry("playerId", "player-1");
    }

    @Test
    @DisplayName("secret이 일치하지 않는 CONNECT 프레임은 세션 attributes에 아무것도 저장하지 않는다.")
    void preSend_secret이_일치하지_않으면_세션에_저장하지_않는다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setNativeHeader("roomCode", "ABCD");
        accessor.setNativeHeader("playerId", "player-1");
        accessor.setNativeHeader("secret", "wrong");
        GameRoom room = mock(GameRoom.class);
        given(room.isSecretValid("player-1", "wrong")).willReturn(false);
        given(gameRegistry.find("ABCD")).willReturn(Optional.of(room));

        // when
        interceptor.preSend(toMessage(accessor), channel);

        // then
        assertThat(sessionAttributes).isEmpty();
    }

    @Test
    @DisplayName("secret 헤더가 없는 CONNECT 프레임은 세션 attributes에 아무것도 저장하지 않는다.")
    void preSend_secret_헤더가_없으면_세션에_저장하지_않는다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setNativeHeader("roomCode", "ABCD");
        accessor.setNativeHeader("playerId", "player-1");

        // when
        interceptor.preSend(toMessage(accessor), channel);

        // then
        assertThat(sessionAttributes).isEmpty();
    }

    @Test
    @DisplayName("식별 헤더가 없는 CONNECT 프레임은 세션 attributes에 아무것도 저장하지 않는다.")
    void preSend_헤더가_없으면_세션에_저장하지_않는다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(sessionAttributes);

        // when
        interceptor.preSend(toMessage(accessor), channel);

        // then
        assertThat(sessionAttributes).isEmpty();
    }

    @Test
    @DisplayName("roomCode 헤더만 있는 CONNECT 프레임은 세션 attributes에 아무것도 저장하지 않는다.")
    void preSend_헤더가_일부만_있으면_세션에_저장하지_않는다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setNativeHeader("roomCode", "ABCD");

        // when
        interceptor.preSend(toMessage(accessor), channel);

        // then
        assertThat(sessionAttributes).isEmpty();
    }

    @Test
    @DisplayName("CONNECT가 아닌 프레임은 세션 attributes를 변경하지 않는다.")
    void preSend_CONNECT가_아니면_세션을_변경하지_않는다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setNativeHeader("roomCode", "ABCD");
        accessor.setNativeHeader("playerId", "player-1");

        // when
        interceptor.preSend(toMessage(accessor), channel);

        // then
        assertThat(sessionAttributes).isEmpty();
    }

    private Message<byte[]> toMessage(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
