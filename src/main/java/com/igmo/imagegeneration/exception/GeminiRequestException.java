package com.igmo.imagegeneration.exception;

import lombok.Getter;

@Getter
public class GeminiRequestException extends ImageGenerationException {

    private final Integer httpStatus;
    private final String providerStatus;
    private final String providerMessage;
    private final String model;
    private final String imageSize;

    public GeminiRequestException(String message, String model, String imageSize, Throwable cause) {
        super(message, cause);
        this.httpStatus = null;
        this.providerStatus = null;
        this.providerMessage = null;
        this.model = model;
        this.imageSize = imageSize;
    }

    public GeminiRequestException(int httpStatus, String model, String imageSize) {
        this(httpStatus, model, imageSize, null, null);
    }

    public GeminiRequestException(
            int httpStatus,
            String model,
            String imageSize,
            String providerStatus,
            String providerMessage
    ) {
        super("Gemini 이미지 생성 요청에 실패했습니다. status=" + httpStatus);
        this.httpStatus = httpStatus;
        this.providerStatus = providerStatus;
        this.providerMessage = providerMessage;
        this.model = model;
        this.imageSize = imageSize;
    }

}
