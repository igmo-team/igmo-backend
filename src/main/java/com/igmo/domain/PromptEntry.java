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

    // 마감 시 생성 과정을 건너뛰고 미리 준비한 샘플 프롬프트-이미지를 바로 READY로 확정한다.
    public void fillWithSample(String prompt, String imageUrl, Instant assignedAt) {
        this.prompt = prompt;
        this.imageUrl = imageUrl;
        this.submittedAt = assignedAt;
        this.status = PromptEntryStatus.READY;
    }

    public boolean isSubmitted() {
        return status != PromptEntryStatus.WAITING;
    }

    // 마감 후 샘플로 READY가 된 엔트리를 뒤늦게 도착한 생성 결과가 덮어쓰지 못하도록 생성 중일 때만 반영한다.
    public void completeImageGeneration(String imageUrl) {
        if (!isGenerating()) {
            return;
        }
        this.imageUrl = imageUrl;
        this.status = PromptEntryStatus.READY;
    }

    public void failImageGeneration() {
        if (!isGenerating()) {
            return;
        }
        this.imageUrl = null;
        this.status = PromptEntryStatus.FAILED;
    }

    public boolean isWaiting() {
        return status == PromptEntryStatus.WAITING;
    }

    private boolean isGenerating() {
        return status == PromptEntryStatus.GENERATING;
    }

    public boolean isImageGenerated() {
        return status == PromptEntryStatus.READY;
    }
}
