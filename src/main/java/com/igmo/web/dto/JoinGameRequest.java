package com.igmo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "게임 방 참여 요청")
public record JoinGameRequest(
        @Schema(description = "참여자 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다.", example = "guest")
        @NotBlank(message = "닉네임을 입력해주세요.")
        String nickname
) {
}
