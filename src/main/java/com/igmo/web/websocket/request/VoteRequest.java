package com.igmo.web.websocket.request;

import jakarta.validation.constraints.NotBlank;

public record VoteRequest(
        @NotBlank(message = "투표할 보기를 선택해주세요.")
        String optionId
) {
}
