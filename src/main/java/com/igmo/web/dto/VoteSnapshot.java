package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Round;
import com.igmo.domain.Vote;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 정답 보기의 위치와 각 표의 대상(voter → option)은 결과 공개 전까지 노출하지 않는다.
public record VoteSnapshot(
        String roomCode,
        GamePhase phase,
        int roundNumber,
        List<VoteOptionView> voteOptions,
        Instant voteStartedAt,
        Instant voteDeadline,
        List<VoteEntryView> voteEntries
) {

    public static VoteSnapshot from(GameRoom room) {
        Round round = room.getCurrentRound();
        Set<String> votedPlayerIds = round.getVotes().stream()
                .map(Vote::getVoterId)
                .collect(Collectors.toSet());

        List<VoteOptionView> voteOptions = round.getVoteOptions().stream()
                .map(VoteOptionView::from)
                .toList();
        List<VoteEntryView> voteEntries = room.getPlayers().stream()
                .filter(player -> !player.getId().equals(round.getQuestionerId()))
                .map(player -> new VoteEntryView(PlayerView.from(player), votedPlayerIds.contains(player.getId())))
                .toList();

        return new VoteSnapshot(
                room.getCode(),
                room.getPhase(),
                round.getRoundNumber(),
                voteOptions,
                room.getVoteStartedAt(),
                room.getVoteDeadline(),
                voteEntries
        );
    }
}
