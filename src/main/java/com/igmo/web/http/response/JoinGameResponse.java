package com.igmo.web.http.response;

import com.igmo.web.LobbySnapshot;

public record JoinGameResponse(
        String playerId,
        String secret,
        LobbySnapshot snapshot
) {
}
