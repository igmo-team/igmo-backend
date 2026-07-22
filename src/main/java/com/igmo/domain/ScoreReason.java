package com.igmo.domain;

public enum ScoreReason {
    CORRECT_ANSWER("정답"),
    FOOLED_PLAYER("낚시"),
    QUESTIONER("출제자");

    private final String label;

    ScoreReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
