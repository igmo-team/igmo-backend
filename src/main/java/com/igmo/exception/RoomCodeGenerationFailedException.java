package com.igmo.exception;

public class RoomCodeGenerationFailedException extends RuntimeException {

    public RoomCodeGenerationFailedException() {
        super("방 코드를 발급하지 못했습니다. 잠시 후 다시 시도해주세요.");
    }
}
