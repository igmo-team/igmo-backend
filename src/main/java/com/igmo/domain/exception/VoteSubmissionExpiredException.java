package com.igmo.domain.exception;

public class VoteSubmissionExpiredException extends RuntimeException {

    public VoteSubmissionExpiredException() {
        super("투표 시간이 만료되었습니다.");
    }
}
