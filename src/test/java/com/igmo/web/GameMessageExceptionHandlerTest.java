package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.stream.Collectors.toMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.igmo.domain.PromptSubmissionType;
import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.DuplicateVoteException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessSubmissionExpiredException;
import com.igmo.domain.exception.GuessSubmissionNotAllowedException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.InvalidVoteOptionException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PerfectGuesserVoteNotAllowedException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.domain.exception.SelfVoteNotAllowedException;
import com.igmo.domain.exception.VoteNotAllowedException;
import com.igmo.domain.exception.VoteSubmissionExpiredException;
import com.igmo.domain.exception.VoteSubmissionNotAllowedException;
import com.igmo.web.dto.ErrorResponse;
import com.igmo.web.dto.PromptRequest;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BeanPropertyBindingResult;

class GameMessageExceptionHandlerTest {

    private final GameMessageExceptionHandler handler = new GameMessageExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GameMessageExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("예상 가능한 게임 예외는 요청 세션의 오류 큐로 메시지를 반환한다.")
    void handleGameException_요청_세션에_오류를_반환한다() throws NoSuchMethodException {
        // given
        PlayersNotReadyException exception = new PlayersNotReadyException();
        Method handlerMethod = GameMessageExceptionHandler.class
                .getDeclaredMethod("handleGameException", RuntimeException.class, Message.class);

        // when
        ErrorResponse response = handler.handleGameException(exception, gameMessage("payload", "/app/rooms/ABCD/start"));

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
    @DisplayName("완벽 정답자의 투표 예외는 요청 세션의 오류 큐로 메시지를 반환한다.")
    void handleGameException_PERFECT_투표_예외를_처리한다() throws NoSuchMethodException {
        // given
        PerfectGuesserVoteNotAllowedException exception = new PerfectGuesserVoteNotAllowedException();
        Method handlerMethod = GameMessageExceptionHandler.class
                .getDeclaredMethod("handleGameException", RuntimeException.class, Message.class);

        // when
        ErrorResponse response = handler.handleGameException(exception, gameMessage("payload", "/app/rooms/ABCD/votes"));

        // then
        MessageExceptionHandler annotation = handlerMethod.getAnnotation(MessageExceptionHandler.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.message()).isEqualTo("완벽 정답자는 투표할 수 없습니다.");
            softly.assertThat(annotation.value()).contains(
                    RoundStartNotAllowedException.class,
                    GuessSubmissionNotAllowedException.class,
                    GuessSubmissionExpiredException.class,
                    GuessNotAllowedException.class,
                    PerfectGuesserVoteNotAllowedException.class
            );
            softly.assertThat(annotation.value()).doesNotContain(
                    DuplicateGuessSubmissionException.class,
                    GuessMatchesOthersException.class
            );
        });
    }

    @Test
    @DisplayName("투표 예외는 요청 세션의 오류 큐로 메시지를 반환하도록 핸들러에 등록된다.")
    void handleGameException_투표_예외를_처리한다() throws NoSuchMethodException {
        // given
        SelfVoteNotAllowedException exception = new SelfVoteNotAllowedException();
        Method handlerMethod = GameMessageExceptionHandler.class
                .getDeclaredMethod("handleGameException", RuntimeException.class, Message.class);

        // when
        ErrorResponse response = handler.handleGameException(exception, gameMessage("payload", "/app/rooms/ABCD/votes"));

        // then
        MessageExceptionHandler annotation = handlerMethod.getAnnotation(MessageExceptionHandler.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.message()).isEqualTo("자신이 제출한 추측에는 투표할 수 없습니다.");
            softly.assertThat(annotation.value()).contains(
                    VoteSubmissionNotAllowedException.class,
                    VoteSubmissionExpiredException.class,
                    DuplicateVoteException.class,
                    VoteNotAllowedException.class,
                    SelfVoteNotAllowedException.class,
                    InvalidVoteOptionException.class
            );
        });
    }

    @Test
    @DisplayName("메시지 요청 값 검증 실패는 요청 세션의 오류 큐로 메시지를 반환한다.")
    void handleValidationException_요청_세션에_오류를_반환한다() throws NoSuchMethodException {
        // given
        Method handlerMethod = GameMessageExceptionHandler.class
                .getDeclaredMethod("handleValidationException", MethodArgumentNotValidException.class, Message.class);
        Method controllerMethod = GameMessageController.class.getDeclaredMethod(
                "submitPrompt",
                String.class,
                PromptRequest.class,
                SimpMessageHeaderAccessor.class
        );
        PromptRequest request = new PromptRequest(" ", PromptSubmissionType.NORMAL);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "promptRequest");
        bindingResult.rejectValue("prompt", "NotBlank", "프롬프트를 입력해주세요.");
        Message<PromptRequest> message = gameMessage(request, "/app/rooms/ABCD/prompts");
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                message,
                new MethodParameter(controllerMethod, 1),
                bindingResult
        );

        // when
        ErrorResponse response = handler.handleValidationException(exception, message);

        // then
        SendToUser sendToUser = handlerMethod.getAnnotation(SendToUser.class);
        assertThat(sendToUser).isNotNull();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.message()).isEqualTo("프롬프트를 입력해주세요.");
            softly.assertThat(sendToUser.destinations()).containsExactly("/queue/errors");
            softly.assertThat(sendToUser.broadcast()).isFalse();
        });

        ILoggingEvent loggingEvent = logAppender.list.getLast();
        Map<String, Object> keyValues = keyValues(loggingEvent);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(loggingEvent.getLevel()).isEqualTo(Level.WARN);
            softly.assertThat(loggingEvent.getThrowableProxy()).isNull();
            softly.assertThat(keyValues).containsEntry("event", "game_message_validation_failed");
            softly.assertThat(keyValues).containsEntry("exceptionType", "MethodArgumentNotValidException");
            softly.assertThat(keyValues).containsEntry("reason", "프롬프트를 입력해주세요.");
            softly.assertThat(keyValues).containsEntry("destination", "/app/rooms/ABCD/prompts");
            softly.assertThat(keyValues).containsEntry("roomCode", "ABCD");
            softly.assertThat(keyValues).doesNotContainKey("outcome");
            softly.assertThat(keyValues).doesNotContainKey("exceptionMessage");
            softly.assertThat(loggingEvent.getFormattedMessage())
                    .isEqualTo("프롬프트를 입력해주세요.");
        });
    }

    @Test
    @DisplayName("예상 가능한 게임 예외는 구조화된 WARN 로그로 남긴다.")
    void handleGameException_구조화된_WARN_로그를_남긴다() {
        // given
        InsufficientPlayersException exception = new InsufficientPlayersException(3);
        Message<String> message = gameMessage("payload", "/app/rooms/ABCD/start");

        // when
        handler.handleGameException(exception, message);

        // then
        ILoggingEvent loggingEvent = logAppender.list.getLast();
        Map<String, Object> keyValues = keyValues(loggingEvent);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(loggingEvent.getLevel()).isEqualTo(Level.WARN);
            softly.assertThat(loggingEvent.getThrowableProxy()).isNull();
            softly.assertThat(keyValues).containsEntry("event", "game_message_rejected");
            softly.assertThat(keyValues).containsEntry("exceptionType", "InsufficientPlayersException");
            softly.assertThat(keyValues).containsEntry("reason", "게임을 시작하려면 최소 3명이 필요합니다.");
            softly.assertThat(keyValues).containsEntry("destination", "/app/rooms/ABCD/start");
            softly.assertThat(keyValues).containsEntry("roomCode", "ABCD");
            softly.assertThat(keyValues).doesNotContainKey("outcome");
            softly.assertThat(keyValues).doesNotContainKey("exceptionMessage");
            softly.assertThat(loggingEvent.getFormattedMessage())
                    .isEqualTo("게임을 시작하려면 최소 3명이 필요합니다.");
        });
    }

    @Test
    @DisplayName("예기치 않은 게임 예외는 구조화된 WARN 로그와 stack trace를 남긴다.")
    void handleUnexpectedException_구조화된_WARN_로그와_stack_trace를_남긴다() {
        // given
        IllegalStateException exception = new IllegalStateException("unexpected");
        Message<String> message = gameMessage("payload", "/app/rooms/ABCD/guesses");

        // when
        handler.handleUnexpectedException(exception, message);

        // then
        ILoggingEvent loggingEvent = logAppender.list.getLast();
        Map<String, Object> keyValues = keyValues(loggingEvent);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(loggingEvent.getLevel()).isEqualTo(Level.WARN);
            softly.assertThat(loggingEvent.getThrowableProxy()).isNotNull();
            softly.assertThat(keyValues).containsEntry("event", "game_message_failed");
            softly.assertThat(keyValues).containsEntry("exceptionType", "IllegalStateException");
            softly.assertThat(keyValues).doesNotContainKey("exceptionMessage");
            softly.assertThat(keyValues).containsEntry("destination", "/app/rooms/ABCD/guesses");
            softly.assertThat(keyValues).containsEntry("roomCode", "ABCD");
            softly.assertThat(keyValues).doesNotContainKey("outcome");
            softly.assertThat(loggingEvent.getFormattedMessage()).isEqualTo("unexpected");
        });
    }

    private <T> Message<T> gameMessage(T payload, String destination) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setDestination(destination);
        return MessageBuilder.createMessage(payload, headerAccessor.getMessageHeaders());
    }

    private Map<String, Object> keyValues(ILoggingEvent loggingEvent) {
        return loggingEvent.getKeyValuePairs().stream()
                .collect(toMap(pair -> pair.key, pair -> pair.value));
    }
}
