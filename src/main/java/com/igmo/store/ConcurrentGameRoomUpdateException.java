package com.igmo.store;

public class ConcurrentGameRoomUpdateException extends RuntimeException {

    public ConcurrentGameRoomUpdateException(String roomCode, int attempts) {
        super("게임방 상태가 동시에 변경되어 저장하지 못했습니다. roomCode=" + roomCode + ", attempts=" + attempts);
    }
}
