package com.igmo.domain.exception;

public class RoundAdvanceNotAllowedException extends RuntimeException {

    public RoundAdvanceNotAllowedException() {
        super("라운드를 진행할 수 없는 상태입니다.");
    }
}
