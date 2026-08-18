package com.igmo.web.dto;

import com.igmo.domain.PromptSubmissionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PromptRequest(
        @NotBlank(message = "프롬프트를 입력해주세요.")
        String prompt,
        @NotNull(message = "프롬프트 제출 유형을 입력해주세요.")
        PromptSubmissionType submissionType
) {
}
