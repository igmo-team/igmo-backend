package com.igmo.admin.exception;

public class AdminImageGenerationFailedException extends RuntimeException {

    public AdminImageGenerationFailedException(Throwable cause) {
        super("이미지 생성에 실패했습니다.", cause);
    }
}
