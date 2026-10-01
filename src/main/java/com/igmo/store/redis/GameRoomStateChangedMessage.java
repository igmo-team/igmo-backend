package com.igmo.store.redis;

import com.igmo.store.GameRoomDelivery;
import java.util.List;

public record GameRoomStateChangedMessage(
        String roomCode,
        String sourceInstanceId,
        long roomVersion,
        List<GameRoomDelivery> deliveries
) {

    public GameRoomStateChangedMessage {
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
    }

    public GameRoomStateChangedMessage(String roomCode, String sourceInstanceId) {
        this(roomCode, sourceInstanceId, 0, List.of());
    }
}
