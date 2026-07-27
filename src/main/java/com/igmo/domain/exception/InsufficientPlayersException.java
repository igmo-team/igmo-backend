package com.igmo.domain.exception;

public class InsufficientPlayersException extends RuntimeException {

    public InsufficientPlayersException(int minimumPlayers) {
        super("게임을 시작하려면 최소 " + minimumPlayers + "명이 필요합니다.");
    }
}
