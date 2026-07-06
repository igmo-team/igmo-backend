package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.web.dto.ErrorResponse;
import java.lang.reflect.Method;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.annotation.SendToUser;

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
}
