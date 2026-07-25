package com.igmo.admin.exception;

public class AdminImageStorageException extends RuntimeException {

    public AdminImageStorageException(Throwable cause) {
        super("관리자 이미지의 S3 저장에 실패했습니다.", cause);
    }
}
