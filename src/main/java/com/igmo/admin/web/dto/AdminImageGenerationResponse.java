package com.igmo.admin.web.dto;

public record AdminImageGenerationResponse(
        String imageDataUrl,
        String storageUri,
        String model,
        String imageSize,
        long durationMs
) {
}
