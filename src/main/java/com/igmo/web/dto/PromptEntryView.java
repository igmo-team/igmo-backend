package com.igmo.web.dto;

import com.igmo.domain.PromptStatus;

public record PromptEntryView(
        PlayerView player,
        PromptStatus status
) {
}
