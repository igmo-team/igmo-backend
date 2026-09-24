package com.igmo.store;

public record RoomUpdate<T>(boolean changed, T value) {

    public static <T> RoomUpdate<T> unchanged(T value) {
        return new RoomUpdate<>(false, value);
    }

    public static <T> RoomUpdate<T> changed(T value) {
        return new RoomUpdate<>(true, value);
    }
}
