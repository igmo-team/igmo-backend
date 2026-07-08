package com.igmo.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Getter
public class PromptEntry {

    private final String promptId;
    private final String playerId;
    private String prompt;
    private Instant submittedAt;
    private PromptStatus status;
    private ImageStatus imageStatus;

    private PromptEntry(String playerId) {
        this.promptId = UUID.randomUUID().toString();
        this.playerId = playerId;
        this.status = PromptStatus.WAITING;
        this.imageStatus = ImageStatus.NONE;
    }

    public static PromptEntry waiting(String playerId) {
        return new PromptEntry(playerId);
    }

    public void submit(String prompt, Instant submittedAt) {
        this.prompt = prompt;
        this.submittedAt = submittedAt;
        this.status = PromptStatus.SUBMITTED;
        this.imageStatus = ImageStatus.GENERATING;
    }

    public boolean isSubmitted() {
        return status == PromptStatus.SUBMITTED;
    }

    public void expire() {
        if (isWaiting()) {
            this.status = PromptStatus.EXPIRED;
        }
    }

    public boolean isWaiting() {
        return status == PromptStatus.WAITING;
    }
}
