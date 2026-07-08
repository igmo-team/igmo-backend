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

public record PromptSubmissionSnapshot(
        String roomCode,
        GamePhase phase,
        Instant promptStartedAt,
        Instant promptDeadline,
        List<PromptEntryView> promptEntries
) {

    public static PromptSubmissionSnapshot from(GameRoom room) {
        Map<String, Player> playersById = room.getPlayers().stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        List<PromptEntryView> promptEntries = room.getPromptEntries().stream()
                .map(entry -> toView(entry, playersById))
                .toList();

        return new PromptSubmissionSnapshot(
                room.getCode(),
                room.getPhase(),
                room.getPromptStartedAt(),
                room.getPromptDeadline(),
                promptEntries
        );
    }

    private static PromptEntryView toView(PromptEntry entry, Map<String, Player> playersById) {
        Player player = playersById.get(entry.getPlayerId());
        return new PromptEntryView(PlayerView.from(player), entry.getStatus(), entry.getImageUrl());
    }
}
