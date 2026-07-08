package com.igmo.web.dto;

public record PromptEntryView(
        PlayerView player,
        boolean submitted
) {
}
