package com.igmo.store;

public record GameRoomStateChangedMessage(
        String roomCode,
        String sourceInstanceId
) {
}
