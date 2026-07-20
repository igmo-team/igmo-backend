package com.igmo.web.dto;

public record GuessEntryView(
        PlayerView player,
        boolean submitted
) {
}
