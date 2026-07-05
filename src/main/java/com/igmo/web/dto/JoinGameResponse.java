package com.igmo.web.dto;

public record JoinGameResponse(
        String playerId,
        String secret,
        LobbySnapshot snapshot
) {
}
