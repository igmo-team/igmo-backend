package com.igmo.domain.exception;

public class GuessNotAllowedException extends RuntimeException {

    public GuessNotAllowedException() {
        super("출제자는 추측을 제출할 수 없습니다.");
    }
}
