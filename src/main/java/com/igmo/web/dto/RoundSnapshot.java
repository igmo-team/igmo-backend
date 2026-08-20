package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GuessEntry;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 투표 전 정보 유출을 막기 위해 추측 내용과 정답 프롬프트는 담지 않는다.
public record RoundSnapshot(
        String roomCode,
        GamePhase phase,
        int roundNumber,
        int totalRoundCount,
        PlayerView questioner,
        String imageUrl,
        Instant guessStartedAt,
        Instant guessDeadline,
        Instant finalGuessSubmissionDeadline,
        List<GuessEntryView> guessEntries
) {

    public static RoundSnapshot from(GameRoom room) {
        Round round = room.getCurrentRound();
        Set<String> guessedPlayerIds = round.getGuesses().stream()
                .map(GuessEntry::getPlayerId)
                .collect(Collectors.toSet());

        Player questioner = findPlayer(room, round.getQuestionerId());
        List<GuessEntryView> guessEntries = room.getPlayers().stream()
                .filter(player -> !player.getId().equals(round.getQuestionerId()))
                .map(player -> new GuessEntryView(PlayerView.from(player), guessedPlayerIds.contains(player.getId())))
                .toList();

        return new RoundSnapshot(
                room.getCode(),
                room.getPhase(),
                round.getRoundNumber(),
                room.getTotalRoundCount(),
                PlayerView.from(questioner),
                round.getAnswerEntry().getImageUrl(),
                room.getGuessStartedAt(),
                room.getGuessDeadline(),
                room.getFinalGuessSubmissionDeadline(),
                guessEntries
        );
    }

    private static Player findPlayer(GameRoom room, String playerId) {
        return room.getPlayers().stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }
}
