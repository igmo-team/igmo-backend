package com.igmo.web.dto;

import com.igmo.domain.ScoreReason;

public record ScoreDetailView(
        String reason,
        String label,
        int score
) {

    public static ScoreDetailView of(ScoreReason reason, int score) {
        return new ScoreDetailView(reason.name(), reason.label(), score);
    }
}
