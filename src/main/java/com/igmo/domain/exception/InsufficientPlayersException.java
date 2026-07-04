package com.igmo.domain.exception;

public class InsufficientPlayersException extends RuntimeException {

    public InsufficientPlayersException() {
        super("게임을 시작하려면 최소 3명이 필요합니다.");
    }
}
