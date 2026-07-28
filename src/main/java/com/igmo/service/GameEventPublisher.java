package com.igmo.service;

import com.igmo.monitoring.GameMetrics;
import com.igmo.web.dto.GuessSubmissionSnapshot;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteSnapshot;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
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

    public void publishVote(String code, VoteSnapshot snapshot) {
        publish(code, RoomMessage.voteSnapshot(snapshot));
    }

    public void publishRoundResult(String code, RoundResultSnapshot snapshot) {
        publish(code, RoomMessage.roundResultSnapshot(snapshot));
    }

    public void publish(String code, RoomMessage<?> message) {
        try {
            messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, message);
            gameMetrics.incrementWebsocketBroadcastCount();
        } catch (RuntimeException exception) {
            gameMetrics.incrementWebsocketBroadcastFailure();
            throw exception;
        }
    }

    public void sendImageGenerationEvent(String playerId, ImageGenerationEvent eventSnapshot) {
        messagingTemplate.convertAndSendToUser(playerId, IMAGE_GENERATION_QUEUE, eventSnapshot);
    }

    public void sendGuessSubmission(String playerId, GuessSubmissionSnapshot snapshot) {
        messagingTemplate.convertAndSendToUser(playerId, GUESS_SUBMISSION_QUEUE, snapshot);
    }

    // 투표 진입 시 각 플레이어에게 본인 프롬프트 보기를 개인큐로 알려 프론트에서 선택 불가 처리하도록 한다.
    public void sendOwnVoteOption(String playerId, OwnVoteOptionNotice notice) {
        messagingTemplate.convertAndSendToUser(playerId, VOTE_OWN_OPTION_QUEUE, notice);
    }
}
