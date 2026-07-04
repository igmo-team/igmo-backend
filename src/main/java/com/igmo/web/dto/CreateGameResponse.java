package com.igmo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "게임 방 생성 응답")
public record CreateGameResponse(
        @Schema(description = "생성된 방 코드", example = "ABCD")
        String roomCode,

        @Schema(description = "호스트 플레이어 ID", example = "host-id")
        String playerId,

        @Schema(description = "호스트 플레이어 인증 secret", example = "host-secret")
        String secret,

        @Schema(description = "생성 직후 로비 상태")
        LobbySnapshot snapshot
) {
}
