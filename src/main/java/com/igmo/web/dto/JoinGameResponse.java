package com.igmo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "게임 방 참여 응답")
public record JoinGameResponse(
        @Schema(description = "참여한 플레이어 ID", example = "guest-id")
        String playerId,

        @Schema(description = "참여한 플레이어 인증 secret", example = "guest-secret")
        String secret,

        @Schema(description = "참여 직후 로비 상태")
        LobbySnapshot snapshot
) {
}
