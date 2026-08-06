package com.igmo.web;

import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.DuplicateVoteException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessSubmissionExpiredException;
import com.igmo.domain.exception.GuessSubmissionNotAllowedException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.InvalidVoteOptionException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PerfectGuesserVoteNotAllowedException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.domain.exception.SelfVoteNotAllowedException;
import com.igmo.domain.exception.VoteNotAllowedException;
import com.igmo.domain.exception.VoteSubmissionExpiredException;
import com.igmo.domain.exception.VoteSubmissionNotAllowedException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.web.dto.ErrorResponse;
import com.igmo.web.exception.PlayerSessionNotFoundException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

@Slf4j
@ControllerAdvice
public class GameMessageExceptionHandler {

    private static final Pattern ROOM_DESTINATION_PATTERN = Pattern.compile("/rooms/([^/]+)(?:/|$)");

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
            PlayersNotReadyException.class,
            RoundStartNotAllowedException.class,
            GuessSubmissionNotAllowedException.class,
            GuessSubmissionExpiredException.class,
            GuessNotAllowedException.class,
            VoteSubmissionNotAllowedException.class,
            VoteSubmissionExpiredException.class,
            DuplicateVoteException.class,
            VoteNotAllowedException.class,
            PerfectGuesserVoteNotAllowedException.class,
            SelfVoteNotAllowedException.class,
            InvalidVoteOptionException.class
    })
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorResponse handleGameException(RuntimeException exception, Message<?> message) {
        String reason = exception.getMessage();
        logRejectedGameMessage("game_message_rejected", reason, exception, message);
        return new ErrorResponse(reason);
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorResponse handleValidationException(MethodArgumentNotValidException exception, Message<?> message) {
        String validationMessage = Objects.requireNonNull(exception.getBindingResult())
                .getFieldErrors()
                .getFirst()
                .getDefaultMessage();

        logRejectedGameMessage(
                "game_message_validation_failed",
                validationMessage,
                exception,
                message);
        return new ErrorResponse(validationMessage);
    }

    @MessageExceptionHandler(Exception.class)
    public void handleUnexpectedException(Exception exception, Message<?> message) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());

        LoggingEventBuilder loggingEvent = log.atWarn()
                .addKeyValue("event", "game_message_failed")
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .addKeyValue("destination", destination)
                .addKeyValue("roomCode", extractRoomCode(destination));
        loggingEvent.setCause(exception).log("{}", Objects.toString(exception.getMessage(), ""));
    }

    private void logRejectedGameMessage(
            String event,
            String reason,
            Exception exception,
            Message<?> message
    ) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());

        LoggingEventBuilder loggingEvent = log.atWarn()
                .addKeyValue("event", event)
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .addKeyValue("reason", reason)
                .addKeyValue("destination", destination)
                .addKeyValue("roomCode", extractRoomCode(destination));
        loggingEvent.log("{}", reason);
    }

    private String extractRoomCode(String destination) {
        if (destination == null) {
            return null;
        }

        Matcher matcher = ROOM_DESTINATION_PATTERN.matcher(destination);
        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1);
    }
}
