package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import java.util.List;

public record LobbySnapshot(
        String roomCode,
        GamePhase phase,
        String hostId,
        List<PlayerView> players
) {

    public static LobbySnapshot from(GameRoom room) {
        List<PlayerView> players = room.getPlayers().stream()
                .map(PlayerView::from)
                .toList();
        return new LobbySnapshot(room.getCode(), room.getPhase(), room.getHostId(), players);
    }
}
