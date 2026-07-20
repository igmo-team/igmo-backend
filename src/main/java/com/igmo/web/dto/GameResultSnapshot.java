package com.igmo.web.dto;

import com.igmo.domain.GameRoom;
import com.igmo.domain.GamePhase;
import com.igmo.domain.Player;
import java.util.ArrayList;
import java.util.List;

public record GameResultSnapshot(
        String roomCode,
        GamePhase phase,
        List<PlayerRankingView> finalRanking
) {

    public static GameResultSnapshot from(GameRoom room) {
        return new GameResultSnapshot(room.getCode(), room.getPhase(), toRanking(room.getFinalRanking()));
    }

    // 누적 점수가 같으면 같은 순위를 부여하고, 다음 순위는 앞선 인원수만큼 건너뛴다(표준 경쟁 순위).
    private static List<PlayerRankingView> toRanking(List<Player> rankedPlayers) {
        List<PlayerRankingView> ranking = new ArrayList<>();
        int rank = 0;
        int position = 0;
        Integer previousScore = null;
        for (Player player : rankedPlayers) {
            position++;
            if (previousScore == null || player.getScore() != previousScore) {
                rank = position;
                previousScore = player.getScore();
            }
            ranking.add(new PlayerRankingView(PlayerView.from(player), rank, player.getScore()));
        }
        return ranking;
    }
}
