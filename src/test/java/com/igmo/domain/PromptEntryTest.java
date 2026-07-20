package com.igmo.domain;

import java.time.Instant;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptEntryTest {

    @Test
    @DisplayName("대기 상태의 프롬프트는 WAITING 상태로 시작한다.")
    void waiting_상태는_WAITING이다() {
        // given & when
        PromptEntry entry = PromptEntry.waiting("player-id");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.WAITING);
            softly.assertThat(entry.getImageUrl()).isNull();
        });
    }

    @Test
    @DisplayName("프롬프트를 제출하면 이미지 생성중 상태로 바꾼다.")
    void submit_이미지_상태를_GENERATING으로_바꾼다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");

        // when
        entry.submit("프롬프트", Instant.parse("2026-07-08T10:00:00Z"));

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(entry.getImageUrl()).isNull();
        });
    }

    @Test
    @DisplayName("이미지 생성이 완료되면 이미지 URL을 저장하고 READY 상태로 바꾼다.")
    void completeImageGeneration_이미지_URL을_저장하고_READY로_바꾼다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");
        entry.submit("고양이가 피아노를 치는 장면", Instant.parse("2026-07-06T10:00:00Z"));

        // when
        entry.completeImageGeneration("https://cdn.example.com/images/prompt-1.png");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/images/prompt-1.png");
            softly.assertThat(entry.isImageGenerated()).isTrue();
        });
    }

    @Test
    @DisplayName("이미지 생성이 실패하면 이미지 URL 없이 FAILED 상태로 바꾼다.")
    void failImageGeneration_이미지_URL_없이_FAILED로_바꾼다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");
        entry.submit("고양이가 피아노를 치는 장면", Instant.parse("2026-07-06T10:00:00Z"));

        // when
        entry.failImageGeneration();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.FAILED);
            softly.assertThat(entry.getImageUrl()).isNull();
            softly.assertThat(entry.isImageGenerated()).isFalse();
        });
    }
}
