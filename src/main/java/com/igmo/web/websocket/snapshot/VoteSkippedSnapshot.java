package com.igmo.web.websocket.snapshot;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import java.time.Instant;

public record VoteSkippedSnapshot(
        String roomCode,
        int roundNumber,
        GamePhase phase,
        Instant startedAt,
        Instant deadline,
        VoteSkippedReason reason
) {

    public static VoteSkippedSnapshot from(GameRoom room) {
        return new VoteSkippedSnapshot(
                room.getCode(),
                room.getCurrentRound().getRoundNumber(),
                room.getPhase(),
                room.getCurrentRound().getVoteSkippedStartedAt(),
                room.getCurrentRound().getVoteSkippedDeadline(),
                VoteSkippedReason.ALL_PERFECT
        );
    }
}
