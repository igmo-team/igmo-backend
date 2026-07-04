package com.igmo.web.dto;

public record CreateGameResponse(String roomCode, String playerId, String secret, LobbySnapshot snapshot) {
}
