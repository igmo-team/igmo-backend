package com.igmo.domain.exception;

public class GuessSubmissionNotAllowedException extends RuntimeException {

    public GuessSubmissionNotAllowedException() {
        super("추측을 제출할 수 있는 단계가 아닙니다.");
    }
}
