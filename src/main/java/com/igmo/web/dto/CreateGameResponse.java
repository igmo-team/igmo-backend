package com.igmo.web.dto;

public record CreateGameResponse(String roomCode, String playerId, LobbySnapshot snapshot) {
}
