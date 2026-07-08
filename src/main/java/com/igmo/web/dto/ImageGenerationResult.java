package com.igmo.web.dto;

import com.igmo.domain.PromptEntryStatus;

public record ImageGenerationResult(
        String roomCode,
        PromptEntryStatus status,
        String imageUrl
) {
}
