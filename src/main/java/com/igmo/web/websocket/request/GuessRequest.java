package com.igmo.web.websocket.request;

import com.igmo.domain.GuessSubmissionType;
import com.igmo.web.websocket.validation.ValidGuessRequest;
import jakarta.validation.constraints.NotNull;

@ValidGuessRequest
public record GuessRequest(
        String guess,
        @NotNull(message = "추측 제출 유형을 입력해주세요.")
        GuessSubmissionType submissionType
) {

    public GuessRequest(String guess) {
        this(guess, GuessSubmissionType.NORMAL);
    }
}
