package com.igmo.web.dto;

import jakarta.validation.constraints.NotBlank;

public record GuessRequest(
        @NotBlank(message = "추측을 입력해주세요.")
        String guess
) {
}
