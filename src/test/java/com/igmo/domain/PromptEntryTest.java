package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptEntryTest {

    @Test
    @DisplayName("프롬프트의 상태가 대기 상태면 만료 상태로 바꾼다.")
    void expireWaitingPrompt() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");

        // when
        entry.expire();

        // then
        assertThat(entry.getStatus()).isEqualTo(PromptStatus.EXPIRED);
    }

    @Test
    @DisplayName("제출 상태의 프롬프트는 만료 상태로 변경하지 않는다.")
    void expireSubmittedPrompt() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");
        Instant submittedAt = Instant.parse("2026-07-08T10:00:00Z");
        entry.submit("프롬프트", submittedAt);

        // when
        entry.expire();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptStatus.SUBMITTED);
            softly.assertThat(entry.getPrompt()).isEqualTo("프롬프트");
            softly.assertThat(entry.getSubmittedAt()).isEqualTo(submittedAt);
        });
    }
}
