package com.igmo.admin.exception;

public class AdminImageGenerationBusyException extends RuntimeException {

    public AdminImageGenerationBusyException() {
        super("관리자 이미지 생성 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
    }
}
