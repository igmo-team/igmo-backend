package com.igmo.web.dto;

import com.igmo.domain.GameRoom;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GuessEntry;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import com.igmo.domain.RoundResult;
import com.igmo.domain.VoteOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 라운드가 끝난 뒤이므로 투표 전과 달리 정답 위치와 각 추측의 작성자, 득표 현황을 모두 공개한다.
public record RoundResultSnapshot(
        String roomCode,
        GamePhase phase,
        int roundNumber,
        int totalRoundCount,
        PlayerView questioner,
        String answerText,
        Instant resultDeadline,
        List<RoundResultView> results,
        List<PlayerView> players
) {

    public static RoundResultSnapshot from(GameRoom room) {
        Round round = room.getCurrentRound();
        RoundResult result = round.getResult();
        Map<String, Player> playersById = room.getPlayers().stream()
                .collect(Collectors.toMap(Player::getId, player -> player));

        // 투표 화면에서 본 보기 순서 그대로 결과를 돌려줘 클라이언트가 자리에서 그대로 공개할 수 있게 한다.
        List<RoundResultView> results = round.getVoteOptions().stream()
                .map(option -> toResultView(option, round, result, playersById))
                .toList();
        List<PlayerView> players = room.getPlayers().stream()
                .map(PlayerView::from)
                .toList();

        return new RoundResultSnapshot(
                room.getCode(),
                room.getPhase(),
                round.getRoundNumber(),
                room.getTotalRoundCount(),
                PlayerView.from(playersById.get(round.getQuestionerId())),
                round.getAnswerEntry().getPrompt(),
                room.getResultDeadline(),
                results,
                players
        );
    }

    private static RoundResultView toResultView(
            VoteOption option,
            Round round,
            RoundResult result,
            Map<String, Player> playersById
    ) {
        boolean isAnswer = option.getOptionId().equals(round.getAnswerEntry().getPromptId());
        String ownerId = isAnswer ? round.getQuestionerId() : findGuesserId(round, option.getOptionId());
        return new RoundResultView(
                PlayerView.from(playersById.get(ownerId)),
                option.getText(),
                isAnswer,
                result.getVoteCountsByOptionId().getOrDefault(option.getOptionId(), 0),
                result.getRoundScoreByPlayerId().getOrDefault(ownerId, 0)
        );
    }

    private static String findGuesserId(Round round, String optionId) {
        return round.getGuesses().stream()
                .filter(guess -> guess.getGuessId().equals(optionId))
                .map(GuessEntry::getPlayerId)
                .findFirst()
                .orElseThrow();
    }
}
