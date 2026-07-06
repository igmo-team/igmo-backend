package com.igmo.domain.exception;

public class PromptSubmissionNotAllowedException extends RuntimeException {

    public PromptSubmissionNotAllowedException() {
        super("프롬프트를 제출할 수 있는 단계가 아닙니다.");
    }
}
