package com.igmo.imagegeneration.exception;

import java.util.List;
import lombok.Getter;

@Getter
public class GeminiResponseException extends ImageGenerationException {

    private final List<String> modelOutputContentTypes;
    private final int httpStatus;
    private final String model;
    private final String imageSize;

    public GeminiResponseException(
            String message,
            List<String> modelOutputContentTypes,
            int httpStatus,
            String model,
            String imageSize
    ) {
        super(message);
        this.modelOutputContentTypes = List.copyOf(modelOutputContentTypes);
        this.httpStatus = httpStatus;
        this.model = model;
        this.imageSize = imageSize;
    }

    public GeminiResponseException(
            String message,
            int httpStatus,
            String model,
            String imageSize,
            Throwable cause
    ) {
        super(message, cause);
        this.modelOutputContentTypes = List.of();
        this.httpStatus = httpStatus;
        this.model = model;
        this.imageSize = imageSize;
    }

}
