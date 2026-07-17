package com.igmo.service.exception;

public class ImageStorageException extends ImageGenerationException {

    public ImageStorageException(Throwable cause) {
        super("S3 이미지 저장에 실패했습니다.", cause);
    }
}
