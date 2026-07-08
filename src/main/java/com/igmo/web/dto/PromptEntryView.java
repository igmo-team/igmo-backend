package com.igmo.web.dto;

import com.igmo.domain.PromptEntryStatus;

public record PromptEntryView(
        PlayerView player,
        PromptEntryStatus status,
        String imageUrl
) {
}
