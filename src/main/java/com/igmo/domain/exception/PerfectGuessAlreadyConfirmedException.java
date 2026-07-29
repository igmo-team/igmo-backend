package com.igmo.domain.exception;

public class PerfectGuessAlreadyConfirmedException extends RuntimeException {

    public PerfectGuessAlreadyConfirmedException() {
        super("이미 완벽 정답을 맞혔습니다. 투표용 가짜 프롬프트를 입력하세요.");
    }
}
