package com.igmo.store.redis;

public record GameRoomStateChangedMessage(
        String roomCode,
        String sourceInstanceId
) {
}
