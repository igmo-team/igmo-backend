package com.igmo.domain.exception;

public class PromptSubmissionExpiredException extends RuntimeException {

    public PromptSubmissionExpiredException() {
        super("프롬프트 제출 시간이 만료되었습니다.");
    }
}
