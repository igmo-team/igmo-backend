package com.igmo.domain.exception;

public class RoundStartNotAllowedException extends RuntimeException {

    public RoundStartNotAllowedException() {
        super("라운드를 시작할 수 없는 상태입니다.");
    }
}
