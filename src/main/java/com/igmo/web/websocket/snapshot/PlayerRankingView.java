package com.igmo.web.websocket.snapshot;

import com.igmo.web.PlayerView;

public record PlayerRankingView(
        PlayerView player,
        int rank,
        int totalScore
) {
}
