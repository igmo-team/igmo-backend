package com.igmo.imagegeneration;

public record ImageGenerationRequest(
        String prompt,
        String model,
        String imageSize
) {
}
