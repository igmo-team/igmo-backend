package com.igmo.domain.exception;

public class SelfVoteNotAllowedException extends RuntimeException {

    public SelfVoteNotAllowedException() {
        super("자신이 제출한 추측에는 투표할 수 없습니다.");
    }
}
