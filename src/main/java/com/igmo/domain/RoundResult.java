package com.igmo.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public class RoundResult {

    private final Map<String, Map<ScoreReason, Integer>> scoreDetailsByPlayerId;

    private RoundResult(Map<String, Map<ScoreReason, Integer>> scoreDetailsByPlayerId) {
        this.scoreDetailsByPlayerId = deepCopy(scoreDetailsByPlayerId);
    }

    public static RoundResult of(Map<String, Map<ScoreReason, Integer>> scoreDetailsByPlayerId) {
        return new RoundResult(scoreDetailsByPlayerId);
    }

    // 플레이어가 이번 라운드에 얻은 점수를 유형별로 돌려준다. 얻은 점수가 없는 유형은 담지 않는다.
    public Map<ScoreReason, Integer> getScoreDetails(String playerId) {
        return scoreDetailsByPlayerId.getOrDefault(playerId, Map.of());
    }

    public int getRoundScore(String playerId) {
        return getScoreDetails(playerId).values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    public Map<String, Integer> getRoundScoreByPlayerId() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        scoreDetailsByPlayerId.keySet().forEach(playerId -> totals.put(playerId, getRoundScore(playerId)));
        return totals;
    }

    private static Map<String, Map<ScoreReason, Integer>> deepCopy(
            Map<String, Map<ScoreReason, Integer>> source
    ) {
        Map<String, Map<ScoreReason, Integer>> copy = new LinkedHashMap<>();
        source.forEach((playerId, details) -> copy.put(playerId, Map.copyOf(details)));
        return Map.copyOf(copy);
    }
}
