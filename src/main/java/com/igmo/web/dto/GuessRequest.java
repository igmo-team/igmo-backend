package com.igmo.web.dto;

import com.igmo.domain.GuessSubmissionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GuessRequest(
        @NotBlank(message = "추측을 입력해주세요.")
        String guess,
        @NotNull(message = "추측 제출 유형을 입력해주세요.")
        GuessSubmissionType submissionType
) {

    public GuessRequest(String guess) {
        this(guess, GuessSubmissionType.NORMAL);
    }
}
