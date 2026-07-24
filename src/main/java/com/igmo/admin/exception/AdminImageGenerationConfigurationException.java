package com.igmo.admin.exception;

public class AdminImageGenerationConfigurationException extends RuntimeException {

    public AdminImageGenerationConfigurationException() {
        super("관리자 이미지 생성 옵션이 설정되지 않았습니다.");
    }
}
