package com.igmo.domain.exception;

public class InvalidVoteOptionException extends RuntimeException {

    public InvalidVoteOptionException() {
        super("존재하지 않는 투표 보기입니다.");
    }
}
