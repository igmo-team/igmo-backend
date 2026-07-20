package com.igmo.domain;

import lombok.Getter;

@Getter
public class VoteOption {

    private final String optionId;
    private final String text;

    private VoteOption(String optionId, String text) {
        this.optionId = optionId;
        this.text = text;
    }

    public static VoteOption of(String optionId, String text) {
        return new VoteOption(optionId, text);
    }
}
