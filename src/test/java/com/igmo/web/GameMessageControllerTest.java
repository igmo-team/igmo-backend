package com.igmo.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.Map;

import com.igmo.service.GameService;
import com.igmo.web.dto.ReadyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

class GameMessageControllerTest {

    private final GameService gameService = mock(GameService.class);
    private final GameMessageController controller = new GameMessageController(gameService);

    @Test
    @DisplayName("세션의 playerId로 준비 상태 변경을 서비스에 위임한다.")
    void changeReady_세션_playerId로_서비스에_위임한다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = headerAccessorWithPlayerId("player-1");

        // when
        controller.changeReady("ABCD", new ReadyRequest(true), headerAccessor);

        // then
        verify(gameService).changeReady("ABCD", "player-1", true);
    }

    @Test
    @DisplayName("세션에 playerId가 없으면 서비스를 호출하지 않는다.")
    void changeReady_세션에_playerId가_없으면_서비스를_호출하지_않는다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(new HashMap<>());

        // when
        controller.changeReady("ABCD", new ReadyRequest(true), headerAccessor);

        // then
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("세션 attributes 자체가 없으면 서비스를 호출하지 않는다.")
    void changeReady_세션_attributes가_없으면_서비스를_호출하지_않는다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();

        // when
        controller.changeReady("ABCD", new ReadyRequest(true), headerAccessor);

        // then
        verifyNoInteractions(gameService);
    }

    private SimpMessageHeaderAccessor headerAccessorWithPlayerId(String playerId) {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE, playerId);
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(sessionAttributes);
        return headerAccessor;
    }
}
