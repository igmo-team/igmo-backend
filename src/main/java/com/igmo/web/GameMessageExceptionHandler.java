package com.igmo.web;

import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.web.dto.ErrorResponse;
import com.igmo.web.exception.PlayerSessionNotFoundException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

@Slf4j
@ControllerAdvice
public class GameMessageExceptionHandler {

    @MessageExceptionHandler({
            PlayerSessionNotFoundException.class,
            RoomNotFoundException.class,
            PlayerNotFoundException.class,
            GameAlreadyStartedException.class,
            PromptSubmissionNotAllowedException.class,
            PromptSubmissionExpiredException.class,
            DuplicatePromptSubmissionException.class,
            InsufficientPlayersException.class,
            NotHostException.class,
            PlayersNotReadyException.class
    })
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorResponse handleGameException(RuntimeException exception) {
        log.warn("게임 메시지 요청을 처리하지 못했다. message={}", exception.getMessage());
        return new ErrorResponse(exception.getMessage());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorResponse handleValidationException(MethodArgumentNotValidException exception) {
        String message = Objects.requireNonNull(exception.getBindingResult())
                .getFieldErrors()
                .getFirst()
                .getDefaultMessage();

        log.warn("게임 메시지 요청 값이 올바르지 않다. message={}", message);
        return new ErrorResponse(message);
    }

    @MessageExceptionHandler(Exception.class)
    public void handleUnexpectedException(Exception exception) {
        log.warn("게임 메시지 처리에 실패했다.", exception);
    }
}
