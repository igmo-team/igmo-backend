package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptEntryTest {

    @Test
    @DisplayName("대기 상태의 프롬프트는 이미지 상태가 없음으로 시작한다.")
    void waiting_이미지_상태는_NONE이다() {
        // given & when
        PromptEntry entry = PromptEntry.waiting("player-id");

        // then
        assertThat(entry.getImageStatus()).isEqualTo(ImageStatus.NONE);
    }

    @Test
    @DisplayName("프롬프트를 제출하면 이미지 생성중 상태로 바꾼다.")
    void submit_이미지_상태를_GENERATING으로_바꾼다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");

        // when
        entry.submit("프롬프트", Instant.parse("2026-07-08T10:00:00Z"));

        // then
        assertThat(entry.getImageStatus()).isEqualTo(ImageStatus.GENERATING);
    }

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
