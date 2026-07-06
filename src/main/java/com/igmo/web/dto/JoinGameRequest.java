package com.igmo.web.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinGameRequest(
        @NotBlank(message = "닉네임을 입력해주세요.")
        String nickname
) {
}
