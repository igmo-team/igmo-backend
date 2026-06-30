package com.igmo.exception;

public class RoomFullException extends RuntimeException {

    public RoomFullException() {
        super("방 정원이 가득 찼습니다.");
    }
}
