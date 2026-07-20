package com.igmo.domain.exception;

public class GuessSubmissionExpiredException extends RuntimeException {

    public GuessSubmissionExpiredException() {
        super("추측 제출 시간이 만료되었습니다.");
    }
}
