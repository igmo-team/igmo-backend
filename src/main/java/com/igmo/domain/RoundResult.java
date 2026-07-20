package com.igmo.domain;

import java.util.Map;
import lombok.Getter;

@Getter
public class RoundResult {

    private final String correctOptionId;
    private final Map<String, Integer> voteCountsByOptionId;
    private final Map<String, Integer> roundScoreByPlayerId;

    private RoundResult(
            String correctOptionId,
            Map<String, Integer> voteCountsByOptionId,
            Map<String, Integer> roundScoreByPlayerId
    ) {
        this.correctOptionId = correctOptionId;
        this.voteCountsByOptionId = Map.copyOf(voteCountsByOptionId);
        this.roundScoreByPlayerId = Map.copyOf(roundScoreByPlayerId);
    }

    public static RoundResult of(
            String correctOptionId,
            Map<String, Integer> voteCountsByOptionId,
            Map<String, Integer> roundScoreByPlayerId
    ) {
        return new RoundResult(correctOptionId, voteCountsByOptionId, roundScoreByPlayerId);
    }
}
