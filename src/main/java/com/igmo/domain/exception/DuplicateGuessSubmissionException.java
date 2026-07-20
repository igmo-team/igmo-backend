package com.igmo.domain.exception;

public class DuplicateGuessSubmissionException extends RuntimeException {

    public DuplicateGuessSubmissionException() {
        super("이미 추측을 제출했습니다.");
    }
}
