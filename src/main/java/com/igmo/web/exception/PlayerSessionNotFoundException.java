package com.igmo.web.exception;

public class PlayerSessionNotFoundException extends RuntimeException {

    public PlayerSessionNotFoundException() {
        super("세션에서 플레이어 정보를 찾을 수 없습니다.");
    }
}
