package com.igmo.web.websocket.snapshot;

import com.igmo.domain.VoteOption;

public record VoteOptionView(
        String optionId,
        String text
) {

    public static VoteOptionView from(VoteOption option) {
        return new VoteOptionView(option.getOptionId(), option.getText());
    }
}
