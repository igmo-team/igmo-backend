package com.igmo.store;

import java.util.List;

public record RoomUpdate<T>(boolean changed, T value, List<GameRoomDelivery> deliveries) {

    public RoomUpdate {
        deliveries = List.copyOf(deliveries);
    }

    public static <T> RoomUpdate<T> unchanged(T value) {
        return new RoomUpdate<>(false, value, List.of());
    }

    public static <T> RoomUpdate<T> changed(T value) {
        return new RoomUpdate<>(true, value, List.of());
    }

    public static <T> RoomUpdate<T> changed(T value, List<GameRoomDelivery> deliveries) {
        return new RoomUpdate<>(true, value, deliveries);
    }
}
