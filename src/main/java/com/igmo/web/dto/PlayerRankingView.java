package com.igmo.web.dto;

public record PlayerRankingView(
        PlayerView player,
        int rank,
        int totalScore
) {
}
