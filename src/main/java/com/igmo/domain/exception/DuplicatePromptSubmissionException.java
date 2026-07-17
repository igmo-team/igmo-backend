package com.igmo.domain.exception;

public class DuplicatePromptSubmissionException extends RuntimeException {

    public DuplicatePromptSubmissionException() {
        super("이미 프롬프트를 제출했습니다.");
    }
}
