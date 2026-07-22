package com.igmo.web.dto;

import com.igmo.domain.GameRoom;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GuessEntry;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import com.igmo.domain.RoundResult;
import com.igmo.domain.ScoreReason;
import com.igmo.domain.Vote;
import com.igmo.domain.VoteOption;
import java.time.Instant;
import java.util.Arrays;
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
        Instant startedAt,
        Instant resultDeadline,
        List<RoundResultView> results,
        List<PlayerView> players
) {

    public static RoundResultSnapshot from(GameRoom room) {
        Round round = room.getCurrentRound();
        RoundResult result = round.getResult();
        Map<String, Player> playersById = room.getPlayers().stream()
                .collect(Collectors.toMap(Player::getId, player -> player));
        Map<String, List<PlayerView>> votersByOptionId = votersByOptionId(round, playersById);

        // 투표 화면에서 본 보기 순서 그대로 결과를 돌려줘 클라이언트가 자리에서 그대로 공개할 수 있게 한다.
        List<RoundResultView> results = round.getVoteOptions().stream()
                .map(option -> toResultView(option, round, result, playersById, votersByOptionId))
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
                room.getResultStartedAt(),
                room.getResultDeadline(),
                results,
                players
        );
    }

    private static Map<String, List<PlayerView>> votersByOptionId(Round round, Map<String, Player> playersById) {
        return round.getVotes().stream()
                .collect(Collectors.groupingBy(
                        Vote::getOptionId,
                        Collectors.mapping(
                                vote -> PlayerView.from(playersById.get(vote.getVoterId())),
                                Collectors.toList())));
    }

    private static RoundResultView toResultView(
            VoteOption option,
            Round round,
            RoundResult result,
            Map<String, Player> playersById,
            Map<String, List<PlayerView>> votersByOptionId
    ) {
        boolean isAnswer = option.getOptionId().equals(round.getAnswerEntry().getPromptId());
        String ownerId = isAnswer ? round.getQuestionerId() : findGuesserId(round, option.getOptionId());
        return new RoundResultView(
                PlayerView.from(playersById.get(ownerId)),
                option.getText(),
                isAnswer,
                result.getRoundScore(ownerId),
                votersByOptionId.getOrDefault(option.getOptionId(), List.of()),
                toScoreDetails(result.getScoreDetails(ownerId))
        );
    }

    // 점수 유형은 항상 정답 → 낚시 → 출제자 순으로 노출한다.
    private static List<ScoreDetailView> toScoreDetails(Map<ScoreReason, Integer> details) {
        return Arrays.stream(ScoreReason.values())
                .filter(details::containsKey)
                .map(reason -> ScoreDetailView.of(reason, details.get(reason)))
                .toList();
    }

    private static String findGuesserId(Round round, String optionId) {
        return round.getGuesses().stream()
                .filter(guess -> guess.getGuessId().equals(optionId))
                .map(GuessEntry::getPlayerId)
                .findFirst()
                .orElseThrow();
    }
}
