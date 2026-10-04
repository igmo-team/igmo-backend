package com.igmo.store;

import java.util.List;

public record RoomUpdate<T>(boolean changed, T value, List<GameRoomDelivery> deliveries, boolean deleted) {

    public RoomUpdate(boolean changed, T value, List<GameRoomDelivery> deliveries) {
        this(changed, value, deliveries, false);
    }

    public RoomUpdate {
        deliveries = List.copyOf(deliveries);
        if (deleted && !changed) {
            throw new IllegalArgumentException("삭제된 방 변경은 변경 상태여야 합니다.");
        }
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

    public static <T> RoomUpdate<T> deleted(T value, List<GameRoomDelivery> deliveries) {
        return new RoomUpdate<>(true, value, deliveries, true);
    }
}
