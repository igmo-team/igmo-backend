package com.igmo.imagegeneration.exception;

public abstract class ImageGenerationException extends RuntimeException {

    protected ImageGenerationException(String message) {
        super(message);
    }

    protected ImageGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
