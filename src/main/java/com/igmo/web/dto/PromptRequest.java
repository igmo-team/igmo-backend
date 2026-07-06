package com.igmo.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PromptRequest(
        @NotBlank
        String prompt
) {
}
