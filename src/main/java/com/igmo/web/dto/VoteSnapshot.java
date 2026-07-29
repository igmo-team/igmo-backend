package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Round;
import java.time.Instant;
import java.util.List;

// 정답 보기의 위치와 각 표의 대상(voter → option)은 결과 공개 전까지 노출하지 않는다.
public record VoteSnapshot(
        String roomCode,
        GamePhase phase,
        int roundNumber,
        List<VoteOptionView> voteOptions,
        Instant voteStartedAt,
        Instant voteDeadline,
        int completedVoteCount,
        int totalVoteCount,
        boolean perfectGuessExists
) {

    public static VoteSnapshot from(GameRoom room) {
        Round round = room.getCurrentRound();
        List<String> playerIds = room.getPlayers().stream().map(player -> player.getId()).toList();
        List<VoteOptionView> voteOptions = round.getVoteOptions().stream()
                .map(VoteOptionView::from)
                .toList();

        return new VoteSnapshot(
                room.getCode(),
                room.getPhase(),
                round.getRoundNumber(),
                voteOptions,
                room.getVoteStartedAt(),
                room.getVoteDeadline(),
                round.getCompletedVoteCount(playerIds),
                round.getTotalVoteCount(playerIds),
                round.hasPerfectGuesser(playerIds)
        );
    }
}
