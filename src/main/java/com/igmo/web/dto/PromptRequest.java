package com.igmo.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PromptRequest(
        @NotBlank(message = "프롬프트를 입력해주세요.")
        String prompt
) {
}
