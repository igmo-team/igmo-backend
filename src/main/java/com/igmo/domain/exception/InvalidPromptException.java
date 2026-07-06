package com.igmo.domain.exception;

public class InvalidPromptException extends RuntimeException {

    public InvalidPromptException() {
        super("프롬프트는 비어 있을 수 없습니다.");
    }
}
