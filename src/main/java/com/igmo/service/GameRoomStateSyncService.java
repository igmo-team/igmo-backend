package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntry;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.LobbySnapshot;
import com.igmo.web.websocket.message.ImageGenerationEvent;
import com.igmo.web.websocket.message.OwnVoteOptionNotice;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import com.igmo.web.websocket.snapshot.PromptSubmissionSnapshot;
import com.igmo.web.websocket.snapshot.RoundResultSnapshot;
import com.igmo.web.websocket.snapshot.RoundSnapshot;
import com.igmo.web.websocket.snapshot.VoteSkippedSnapshot;
import com.igmo.web.websocket.snapshot.VoteSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameRoomStateSyncService {

    private final GameRoomRepository gameRoomRepository;
    private final GameEventPublisher gameEventPublisher;

    public void sync(String roomCode, String playerId) {
        GameRoom room = gameRoomRepository.synchronizeFromRedisAndGet(roomCode)
                .orElseThrow(RoomNotFoundException::new);
        if (!room.hasPlayer(playerId)) {
            throw new PlayerNotFoundException();
        }

        gameEventPublisher.sendRoomState(playerId, roomCode, snapshotOf(room));
        if (room.getPhase() == GamePhase.VOTING) {
            sendOwnVoteOption(roomCode, playerId, room);
        }
    }

    public void publishRoomSnapshot(GameRoom room) {
        gameEventPublisher.publish(room.getCode(), snapshotOf(room));
    }

    public void publishOwnVoteOptions(GameRoom room) {
        if (room.getPhase() != GamePhase.VOTING) {
            return;
        }
        room.getCurrentRoundOwnVoteOptions().forEach((playerId, ownVoteOption) ->
                gameEventPublisher.sendOwnVoteOption(
                        playerId,
                        OwnVoteOptionNotice.of(
                                room.getCode(),
                                room.getCurrentRound().getRoundNumber(),
                                ownVoteOption
                        )
                ));
    }

    public void publishImageResult(GameRoom room, String playerId) {
        room.findPromptEntry(playerId).ifPresent(entry -> gameEventPublisher.sendImageGenerationEvent(
                playerId,
                imageGenerationEvent(room.getCode(), entry)));
    }

    private ImageGenerationEvent imageGenerationEvent(String roomCode, PromptEntry entry) {
        return new ImageGenerationEvent(
                roomCode,
                entry.getStatus(),
                entry.getPrompt(),
                entry.getImageUrl(),
                entry.getErrorMessage());
    }

    private RoomMessage<?> snapshotOf(GameRoom room) {
        return switch (room.getPhase()) {
            case LOBBY -> RoomMessage.lobbySnapshot(LobbySnapshot.from(room));
            case GENERATING -> RoomMessage.promptSubmissionSnapshot(PromptSubmissionSnapshot.from(room));
            case PLAYING -> RoomMessage.roundSnapshot(RoundSnapshot.from(room));
            case VOTING -> RoomMessage.voteSnapshot(VoteSnapshot.from(room));
            case VOTE_SKIPPED -> RoomMessage.voteSkippedSnapshot(VoteSkippedSnapshot.from(room));
            case RESULTS -> RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room));
            case ENDED -> RoomMessage.gameResultSnapshot(GameResultSnapshot.from(room));
        };
    }

    private void sendOwnVoteOption(String roomCode, String playerId, GameRoom room) {
        gameEventPublisher.sendOwnVoteOption(
                playerId,
                OwnVoteOptionNotice.of(
                        roomCode,
                        room.getCurrentRound().getRoundNumber(),
                        room.getCurrentRoundOwnVoteOptions().get(playerId)
                )
        );
    }
}
