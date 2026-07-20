package com.igmo.web.dto;

public record VoteEntryView(
        PlayerView player,
        boolean voted
) {
}
