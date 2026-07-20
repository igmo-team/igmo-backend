package com.igmo.domain.exception;

public class DuplicateVoteException extends RuntimeException {

    public DuplicateVoteException() {
        super("이미 투표했습니다.");
    }
}
