package com.igmo.domain.exception;

public class PlayersNotReadyException extends RuntimeException {

    public PlayersNotReadyException() {
        super("모든 참가자가 준비되지 않았습니다.");
    }
}
