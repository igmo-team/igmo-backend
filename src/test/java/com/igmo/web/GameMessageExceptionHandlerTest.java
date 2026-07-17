package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.web.dto.ErrorResponse;
import com.igmo.web.dto.PromptRequest;
import java.lang.reflect.Method;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.validation.BeanPropertyBindingResult;

class GameMessageExceptionHandlerTest {

    private final GameMessageExceptionHandler handler = new GameMessageExceptionHandler();

    @Test
    @DisplayName("예상 가능한 게임 예외는 요청 세션의 오류 큐로 메시지를 반환한다.")
    void handleGameException_요청_세션에_오류를_반환한다() throws NoSuchMethodException {
        // given
        PlayersNotReadyException exception = new PlayersNotReadyException();
        Method handlerMethod = GameMessageExceptionHandler.class
                .getDeclaredMethod("handleGameException", RuntimeException.class);

        // when
        ErrorResponse response = handler.handleGameException(exception);

        // then
        SendToUser sendToUser = handlerMethod.getAnnotation(SendToUser.class);
        assertThat(sendToUser).isNotNull();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.message()).isEqualTo("모든 참가자가 준비되지 않았습니다.");
            softly.assertThat(sendToUser.destinations()).containsExactly("/queue/errors");
            softly.assertThat(sendToUser.broadcast()).isFalse();
        });
    }

    @Test
    @DisplayName("메시지 요청 값 검증 실패는 요청 세션의 오류 큐로 메시지를 반환한다.")
    void handleValidationException_요청_세션에_오류를_반환한다() throws NoSuchMethodException {
        // given
        Method handlerMethod = GameMessageExceptionHandler.class
                .getDeclaredMethod("handleValidationException", MethodArgumentNotValidException.class);
        Method controllerMethod = GameMessageController.class.getDeclaredMethod(
                "submitPrompt",
                String.class,
                PromptRequest.class,
                SimpMessageHeaderAccessor.class
        );
        PromptRequest request = new PromptRequest(" ");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "promptRequest");
        bindingResult.rejectValue("prompt", "NotBlank", "프롬프트를 입력해주세요.");
        Message<PromptRequest> message = MessageBuilder.withPayload(request).build();
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                message,
                new MethodParameter(controllerMethod, 1),
                bindingResult
        );

        // when
        ErrorResponse response = handler.handleValidationException(exception);

        // then
        SendToUser sendToUser = handlerMethod.getAnnotation(SendToUser.class);
        assertThat(sendToUser).isNotNull();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.message()).isEqualTo("프롬프트를 입력해주세요.");
            softly.assertThat(sendToUser.destinations()).containsExactly("/queue/errors");
            softly.assertThat(sendToUser.broadcast()).isFalse();
        });
    }
}
