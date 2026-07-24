package com.igmo.admin.exception;

public class InvalidAdminImageGenerationRequestException extends RuntimeException {

    public InvalidAdminImageGenerationRequestException(String fieldName) {
        super("허용되지 않은 %s 값입니다.".formatted(fieldName));
    }
}
