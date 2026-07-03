package com.igmo.service.exception;

public class UnauthorizedPlayerException extends RuntimeException {

    public UnauthorizedPlayerException() {
        super("본인만 퇴장할 수 있습니다.");
    }
}
