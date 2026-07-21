package com.igmo.service;

import com.igmo.web.dto.ImageGenerationResult;
import com.igmo.web.dto.LobbySnapshot;
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

    private final SimpMessagingTemplate messagingTemplate;

    public GameEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
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
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + code, message);
    }

    public void sendImageGenerationResult(String playerId, ImageGenerationResult result) {
        messagingTemplate.convertAndSendToUser(playerId, IMAGE_GENERATION_QUEUE, result);
    }
}
