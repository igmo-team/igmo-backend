package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "로비 상태 스냅샷")
public record LobbySnapshot(
        @Schema(description = "방 코드", example = "ABCD")
        String roomCode,

        @Schema(description = "게임 진행 단계", example = "LOBBY")
        GamePhase phase,

        @Schema(description = "현재 호스트 플레이어 ID", example = "host-id")
        String hostId,

        @Schema(description = "방에 참여 중인 플레이어 목록")
        List<PlayerView> players
) {

    public static LobbySnapshot from(GameRoom room) {
        List<PlayerView> players = room.getPlayers().stream()
                .map(PlayerView::from)
                .toList();
        return new LobbySnapshot(room.getCode(), room.getPhase(), room.getHostId(), players);
    }
}
