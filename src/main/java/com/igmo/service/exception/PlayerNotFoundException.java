package com.igmo.service.exception;

public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException() {
        super("방에 없는 플레이어입니다.");
    }
}
