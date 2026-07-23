package com.igmo.web.dto;

import com.igmo.domain.PromptEntryStatus;

public record ImageGenerationResult(
        String roomCode,
        PromptEntryStatus status,
        String prompt,
        String imageUrl,
        String errorMessage
) {

    public ImageGenerationResult(String roomCode, PromptEntryStatus status, String prompt, String imageUrl) {
        this(roomCode, status, prompt, imageUrl, null);
    }
}
