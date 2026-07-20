package com.igmo.domain.exception;

public class VoteNotAllowedException extends RuntimeException {

    public VoteNotAllowedException() {
        super("출제자는 투표할 수 없습니다.");
    }
}
