package com.igmo.store;

import com.igmo.domain.GamePhase;
import java.util.List;

public record RedisGameRoomState(
        String roomCode,
        GamePhase phase,
        int currentRound,
        List<PlayerState> players
) {

    public RedisGameRoomState {
        players = List.copyOf(players);
    }

    public record PlayerState(
            String id,
            String nickname,
            int score,
            boolean ready
    ) {
    }
}
