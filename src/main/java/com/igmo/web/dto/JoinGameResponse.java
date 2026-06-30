package com.igmo.web.dto;

public record JoinGameResponse(String playerId, LobbySnapshot snapshot) {
}
