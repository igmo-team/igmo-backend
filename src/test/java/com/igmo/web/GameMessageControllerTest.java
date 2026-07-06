package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.igmo.service.GameService;
import com.igmo.web.dto.PromptRequest;
import com.igmo.web.dto.ReadyRequest;
import com.igmo.web.exception.PlayerSessionNotFoundException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

class GameMessageControllerTest {

    private final GameService gameService = mock(GameService.class);
    private final GameMessageController controller =
            new GameMessageController(gameService, new PlayerSessionResolver());
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

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
    @DisplayName("준비 상태 변경 시 세션에 playerId가 없으면 PlayerSessionNotFoundException을 던진다.")
    void changeReady_세션에_playerId가_없으면_예외를_던진다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(new HashMap<>());

        // when & then
        assertThatThrownBy(() -> controller.changeReady("ABCD", new ReadyRequest(true), headerAccessor))
                .isInstanceOf(PlayerSessionNotFoundException.class)
                .hasMessage("세션에서 플레이어 정보를 찾을 수 없습니다.");
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("준비 상태 변경 시 세션 attributes 자체가 없으면 PlayerSessionNotFoundException을 던진다.")
    void changeReady_세션_attributes가_없으면_예외를_던진다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();

        // when & then
        assertThatThrownBy(() -> controller.changeReady("ABCD", new ReadyRequest(true), headerAccessor))
                .isInstanceOf(PlayerSessionNotFoundException.class)
                .hasMessage("세션에서 플레이어 정보를 찾을 수 없습니다.");
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("세션의 playerId로 게임 시작을 서비스에 위임한다.")
    void startGame_세션_playerId로_서비스에_위임한다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = headerAccessorWithPlayerId("player-1");

        // when
        controller.startGame("ABCD", headerAccessor);

        // then
        verify(gameService).startGame("ABCD", "player-1");
    }

    @Test
    @DisplayName("세션의 playerId로 프롬프트 제출을 서비스에 위임한다.")
    void submitPrompt_세션_playerId로_서비스에_위임한다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = headerAccessorWithPlayerId("player-1");

        // when
        controller.submitPrompt("ABCD", new PromptRequest("프롬프트"), headerAccessor);

        // then
        verify(gameService).submitPrompt("ABCD", "player-1", "프롬프트");
    }

    @Test
    @DisplayName("프롬프트 제출 시 세션에 playerId가 없으면 PlayerSessionNotFoundException을 던진다.")
    void submitPrompt_세션에_playerId가_없으면_예외를_던진다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(new HashMap<>());

        // when & then
        assertThatThrownBy(() -> controller.submitPrompt("ABCD", new PromptRequest("프롬프트"), headerAccessor))
                .isInstanceOf(PlayerSessionNotFoundException.class)
                .hasMessage("세션에서 플레이어 정보를 찾을 수 없습니다.");
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("프롬프트 제출 요청의 prompt가 null이면 검증에 실패한다.")
    void submitPrompt_prompt가_null이면_검증에_실패한다() {
        // when & then
        assertPromptInvalid(new PromptRequest(null));
    }

    @Test
    @DisplayName("프롬프트 제출 요청의 prompt가 공백이면 검증에 실패한다.")
    void submitPrompt_prompt가_공백이면_검증에_실패한다() {
        // when & then
        assertPromptInvalid(new PromptRequest("   "));
    }

    @Test
    @DisplayName("게임 시작 시 세션에 playerId가 없으면 PlayerSessionNotFoundException을 던진다.")
    void startGame_세션에_playerId가_없으면_예외를_던진다() {
        // given
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(new HashMap<>());

        // when & then
        assertThatThrownBy(() -> controller.startGame("ABCD", headerAccessor))
                .isInstanceOf(PlayerSessionNotFoundException.class)
                .hasMessage("세션에서 플레이어 정보를 찾을 수 없습니다.");
        verifyNoInteractions(gameService);
    }

    private SimpMessageHeaderAccessor headerAccessorWithPlayerId(String playerId) {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE, playerId);
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        headerAccessor.setSessionAttributes(sessionAttributes);
        return headerAccessor;
    }

    private void assertPromptInvalid(PromptRequest request) {
        org.assertj.core.api.Assertions.assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .containsExactly("프롬프트를 입력해주세요.");
    }
}
