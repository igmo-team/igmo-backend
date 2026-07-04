package com.igmo.domain.exception;

public class NotHostException extends RuntimeException {

    public NotHostException() {
        super("방장만 게임을 시작할 수 있습니다.");
    }
}
