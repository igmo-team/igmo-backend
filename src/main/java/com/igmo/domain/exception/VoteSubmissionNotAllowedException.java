package com.igmo.domain.exception;

public class VoteSubmissionNotAllowedException extends RuntimeException {

    public VoteSubmissionNotAllowedException() {
        super("투표할 수 있는 단계가 아닙니다.");
    }
}
