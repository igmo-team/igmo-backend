package com.igmo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "게임 방 생성 요청")
public record CreateGameRequest(
        @Schema(description = "호스트 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다.", example = "host")
        @NotBlank(message = "닉네임을 입력해주세요.")
        String nickname
) {
}
