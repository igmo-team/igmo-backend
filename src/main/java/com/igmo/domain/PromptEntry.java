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
    private PromptEntryStatus status;
    private String imageUrl;

    private PromptEntry(String playerId) {
        this.promptId = UUID.randomUUID().toString();
        this.playerId = playerId;
        this.status = PromptEntryStatus.WAITING;
    }

    public static PromptEntry waiting(String playerId) {
        return new PromptEntry(playerId);
    }

    public void submit(String prompt, Instant submittedAt) {
        this.prompt = prompt;
        this.submittedAt = submittedAt;
        this.status = PromptEntryStatus.GENERATING;
    }

    public boolean isSubmitted() {
        return status != PromptEntryStatus.WAITING;
    }

    public void completeImageGeneration(String imageUrl) {
        this.imageUrl = imageUrl;
        this.status = PromptEntryStatus.READY;
    }

    public void failImageGeneration() {
        this.imageUrl = null;
        this.status = PromptEntryStatus.FAILED;
    }

    public boolean isWaiting() {
        return status == PromptEntryStatus.WAITING;
    }
}
