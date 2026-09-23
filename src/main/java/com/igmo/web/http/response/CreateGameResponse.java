package com.igmo.web.http.response;

import com.igmo.web.LobbySnapshot;

public record CreateGameResponse(
        String roomCode,
        String playerId,
        String secret,
        LobbySnapshot snapshot
) {
}
