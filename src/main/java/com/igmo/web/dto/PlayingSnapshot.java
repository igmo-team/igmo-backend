package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.domain.PromptEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record PlayingSnapshot(
        String roomCode,
        GamePhase phase,
        int round,
        String turnId,
        PlayerView imageOwner,
        String imageUrl,
        Instant promptStartedAt,
        Instant promptDeadline,
        boolean promptSubmissionOpen,
        List<PlayerView> players
) {

    public static PlayingSnapshot from(GameRoom room) {
        PromptEntry currentPromptEntry = room.getCurrentRoundPromptEntry();
        Map<String, Player> playersById = room.getPlayers().stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        Player imageOwner = playersById.get(currentPromptEntry.getPlayerId());

        return new PlayingSnapshot(
                room.getCode(),
                room.getPhase(),
                room.getCurrentRound(),
                currentPromptEntry.getPromptId(),
                PlayerView.from(imageOwner),
                currentPromptEntry.getImageUrl(),
                room.getPlayingPromptStartedAt(),
                room.getPlayingPromptDeadline(),
                room.isPlayingPromptSubmissionOpen(),
                room.getPlayers().stream()
                        .map(PlayerView::from)
                        .toList()
        );
    }
}
