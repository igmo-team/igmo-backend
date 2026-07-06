package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

class PlayerSessionResolverTest {

    private final PlayerSessionResolver resolver = new PlayerSessionResolver();

    @Test
    @DisplayName("세션 attributes의 playerId를 반환한다.")
    void resolvePlayerId_playerId가_있으면_반환한다() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE, "player-1");
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(sessionAttributes);

        // when
        String playerId = resolver.resolvePlayerId(headerAccessor);

        // then
        assertThat(playerId).isEqualTo("player-1");
    }

    @Test
    @DisplayName("세션에 playerId가 없으면 null을 반환한다.")
    void resolvePlayerId_playerId가_없으면_null을_반환한다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(new HashMap<>());

        // when
        String playerId = resolver.resolvePlayerId(headerAccessor);

        // then
        assertThat(playerId).isNull();
    }

    @Test
    @DisplayName("세션 attributes 자체가 없으면 null을 반환한다.")
    void resolvePlayerId_세션_attributes가_없으면_null을_반환한다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();

        // when
        String playerId = resolver.resolvePlayerId(headerAccessor);

        // then
        assertThat(playerId).isNull();
    }
}
