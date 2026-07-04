package com.igmo.web.dto;

import com.igmo.domain.Player;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "플레이어 표시 정보")
public record PlayerView(
        @Schema(description = "플레이어 ID", example = "player-id")
        String id,

        @Schema(description = "닉네임", example = "host")
        String nickname,

        @Schema(description = "현재 점수", example = "0")
        int score
) {

    public static PlayerView from(Player player) {
        return new PlayerView(player.getId(), player.getNickname().value(), player.getScore());
    }
}
