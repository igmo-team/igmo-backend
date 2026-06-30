package com.igmo.exception;

public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException() {
        super("방을 찾을 수 없습니다.");
    }
}
