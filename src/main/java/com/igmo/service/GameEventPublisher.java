package com.igmo.service;

import com.igmo.monitoring.GameMetrics;
import com.igmo.monitoring.WebSocketChannelType;
import com.igmo.monitoring.WebSocketMessageOutcome;
import com.igmo.monitoring.WebSocketMessageType;
import com.igmo.domain.GamePhase;
import com.igmo.web.dto.GuessSubmissionSnapshot;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class GameEventPublisher {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";
    private static final String IMAGE_GENERATION_QUEUE = "/queue/image-generation";
    private static final String GUESS_SUBMISSION_QUEUE = "/queue/guess-submission";
    private static final String VOTE_OWN_OPTION_QUEUE = "/queue/vote-own-option";

    private final SimpMessagingTemplate messagingTemplate;
    private final GameMetrics gameMetrics;

    public GameEventPublisher(SimpMessagingTemplate messagingTemplate, GameMetrics gameMetrics) {
        this.messagingTemplate = messagingTemplate;
        this.gameMetrics = gameMetrics;
    }

    public void publishLobby(String code, LobbySnapshot snapshot) {
        publish(code, RoomMessage.lobbySnapshot(snapshot));
    }

    public void publishPromptSubmission(String code, PromptSubmissionSnapshot snapshot) {
        publish(code, RoomMessage.promptSubmissionSnapshot(snapshot));
    }

    public void publishRound(String code, RoundSnapshot snapshot) {
        publish(code, RoomMessage.roundSnapshot(snapshot));
    }

    public void publishRoundResult(String code, RoundResultSnapshot snapshot) {
        publish(code, RoomMessage.roundResultSnapshot(snapshot));
    }

    public void publish(String code, RoomMessage<?> message) {
        String destination = ROOM_TOPIC_PREFIX + code;
        WebSocketMessageType messageType = WebSocketMessageType.from(message.type());
        try {
            messagingTemplate.convertAndSend(destination, message);
            gameMetrics.recordWebSocketMessageSend(
                    messageType,
                    WebSocketChannelType.ROOM_TOPIC,
                    WebSocketMessageOutcome.SUCCESS);
        } catch (RuntimeException exception) {
            gameMetrics.recordWebSocketMessageSend(
                    messageType,
                    WebSocketChannelType.ROOM_TOPIC,
                    WebSocketMessageOutcome.FAILURE);
            LoggingEventBuilder loggingEvent = log.atError()
                    .addKeyValue("event", "room_broadcast_failed")
                    .addKeyValue("roomCode", code)
                    .addKeyValue("phase", phaseOf(message.type()))
                    .addKeyValue("messageType", messageType)
                    .addKeyValue("destination", destination)
                    .addKeyValue("channelType", WebSocketChannelType.ROOM_TOPIC)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName());
            loggingEvent.setCause(exception).log("{}", messageType);
            throw exception;
        }
    }

    public void sendImageGenerationEvent(String playerId, ImageGenerationEvent eventSnapshot) {
        sendPrivateEvent(
                playerId,
                eventSnapshot.roomCode(),
                GamePhase.GENERATING,
                WebSocketMessageType.IMAGE_GENERATION_EVENT,
                IMAGE_GENERATION_QUEUE,
                eventSnapshot);
    }

    public void sendGuessSubmission(String playerId, GamePhase phase, GuessSubmissionSnapshot snapshot) {
        sendPrivateEvent(
                playerId,
                snapshot.roomCode(),
                phase,
                WebSocketMessageType.GUESS_SUBMISSION_RESULT,
                GUESS_SUBMISSION_QUEUE,
                snapshot);
    }

    // 투표 진입 시 각 플레이어에게 본인 프롬프트 보기를 개인큐로 알려 프론트에서 선택 불가 처리하도록 한다.
    public void sendOwnVoteOption(String playerId, OwnVoteOptionNotice notice) {
        sendPrivateEvent(
                playerId,
                notice.roomCode(),
                GamePhase.VOTING,
                WebSocketMessageType.OWN_VOTE_OPTION_NOTICE,
                VOTE_OWN_OPTION_QUEUE,
                notice);
    }

    private void sendPrivateEvent(
            String playerId,
            String roomCode,
            GamePhase phase,
            WebSocketMessageType messageType,
            String destination,
            Object payload
    ) {
        try {
            messagingTemplate.convertAndSendToUser(playerId, destination, payload);
            gameMetrics.recordWebSocketMessageSend(
                    messageType,
                    WebSocketChannelType.PRIVATE_QUEUE,
                    WebSocketMessageOutcome.SUCCESS);
        } catch (RuntimeException exception) {
            gameMetrics.recordWebSocketMessageSend(
                    messageType,
                    WebSocketChannelType.PRIVATE_QUEUE,
                    WebSocketMessageOutcome.FAILURE);
            LoggingEventBuilder loggingEvent = log.atError()
                    .addKeyValue("event", "private_event_send_failed")
                    .addKeyValue("roomCode", roomCode)
                    .addKeyValue("phase", phase)
                    .addKeyValue("messageType", messageType)
                    .addKeyValue("destination", destination)
                    .addKeyValue("channelType", WebSocketChannelType.PRIVATE_QUEUE)
                    .addKeyValue("playerId", playerId)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName());
            loggingEvent.setCause(exception).log("{}", messageType);
            throw exception;
        }
    }

    private GamePhase phaseOf(com.igmo.web.dto.RoomMessageType messageType) {
        return switch (messageType) {
            case LOBBY_SNAPSHOT -> GamePhase.LOBBY;
            case PROMPT_SUBMISSION_SNAPSHOT -> GamePhase.GENERATING;
            case ROUND_SNAPSHOT -> GamePhase.PLAYING;
            case VOTE_SNAPSHOT -> GamePhase.VOTING;
            case ROUND_RESULT_SNAPSHOT -> GamePhase.RESULTS;
            case GAME_RESULT_SNAPSHOT -> GamePhase.ENDED;
        };
    }

}
