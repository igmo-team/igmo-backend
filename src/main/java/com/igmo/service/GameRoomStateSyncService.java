package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.GameResultSnapshot;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteSkippedSnapshot;
import com.igmo.web.dto.VoteSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameRoomStateSyncService {

    private final GameRoomRepository gameRoomRepository;
    private final GameEventPublisher gameEventPublisher;

    public void sync(String roomCode, String playerId) {
        GameRoom room = gameRoomRepository.restore(roomCode)
                .orElseThrow(RoomNotFoundException::new);
        if (!room.hasPlayer(playerId)) {
            throw new PlayerNotFoundException();
        }

        gameEventPublisher.sendRoomState(playerId, roomCode, snapshotOf(room));
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
}
