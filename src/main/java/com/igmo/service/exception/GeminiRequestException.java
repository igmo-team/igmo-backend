package com.igmo.service.exception;

import lombok.Getter;

@Getter
public class GeminiRequestException extends ImageGenerationException {

    private final Integer httpStatus;
    private final String model;
    private final String imageSize;

    public GeminiRequestException(String message, String model, String imageSize, Throwable cause) {
        super(message, cause);
        this.httpStatus = null;
        this.model = model;
        this.imageSize = imageSize;
    }

    public GeminiRequestException(int httpStatus, String model, String imageSize) {
        super("Gemini 이미지 생성 요청에 실패했습니다. status=" + httpStatus);
        this.httpStatus = httpStatus;
        this.model = model;
        this.imageSize = imageSize;
    }

}
