package com.igmo.web.http.request;

import jakarta.validation.constraints.NotBlank;

public record CreateGameRequest(
        @NotBlank(message = "닉네임을 입력해주세요.")
        String nickname
) {
}
