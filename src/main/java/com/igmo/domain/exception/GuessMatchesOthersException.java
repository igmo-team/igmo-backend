package com.igmo.domain.exception;

public class GuessMatchesOthersException extends RuntimeException {

    public GuessMatchesOthersException() {
        super("다른 플레이어의 추측과 동일한 추측은 제출할 수 없습니다.");
    }
}
