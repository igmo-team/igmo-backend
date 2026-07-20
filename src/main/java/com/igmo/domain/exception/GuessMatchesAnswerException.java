package com.igmo.domain.exception;

public class GuessMatchesAnswerException extends RuntimeException {

    public GuessMatchesAnswerException() {
        super("정답 프롬프트와 동일한 추측은 제출할 수 없습니다.");
    }
}
