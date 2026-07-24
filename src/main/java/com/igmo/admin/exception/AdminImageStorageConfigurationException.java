package com.igmo.admin.exception;

public class AdminImageStorageConfigurationException extends RuntimeException {

    public AdminImageStorageConfigurationException() {
        super("관리자 이미지 저장용 S3 bucket이 설정되지 않았습니다.");
    }
}
