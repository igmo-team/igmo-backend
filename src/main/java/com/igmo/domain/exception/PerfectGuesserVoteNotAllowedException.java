package com.igmo.domain.exception;

public class PerfectGuesserVoteNotAllowedException extends RuntimeException {

    public PerfectGuesserVoteNotAllowedException() {
        super("완벽 정답자는 투표할 수 없습니다.");
    }
}
