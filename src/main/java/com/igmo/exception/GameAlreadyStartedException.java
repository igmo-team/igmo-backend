package com.igmo.exception;

public class GameAlreadyStartedException extends RuntimeException {

    public GameAlreadyStartedException() {
        super("이미 시작된 게임입니다.");
    }
}
