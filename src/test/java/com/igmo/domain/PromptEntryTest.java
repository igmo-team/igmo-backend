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
    @DisplayName("WAITING 또는 FAILED 상태의 프롬프트만 다시 제출할 수 있다.")
    void canSubmitPrompt_WAITING_또는_FAILED면_true를_반환한다() {
        // given
        PromptEntry waiting = PromptEntry.waiting("waiting-player");
        PromptEntry generating = PromptEntry.waiting("generating-player");
        generating.submit("프롬프트", Instant.parse("2026-07-08T10:00:00Z"));
        PromptEntry ready = PromptEntry.waiting("ready-player");
        ready.submit("프롬프트", Instant.parse("2026-07-08T10:00:00Z"));
        ready.completeImageGeneration("https://cdn.example.com/images/prompt.png");
        PromptEntry failed = PromptEntry.waiting("failed-player");
        failed.submit("프롬프트", Instant.parse("2026-07-08T10:00:00Z"));
        failed.failImageGeneration();

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(waiting.canSubmitPrompt()).isTrue();
            softly.assertThat(failed.canSubmitPrompt()).isTrue();
            softly.assertThat(generating.canSubmitPrompt()).isFalse();
            softly.assertThat(ready.canSubmitPrompt()).isFalse();
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

    @Test
    @DisplayName("샘플로 채우면 프롬프트와 이미지 URL을 저장하고 READY 상태로 바꾼다.")
    void fillWithSample_프롬프트와_이미지_URL을_저장하고_READY로_바꾼다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");

        // when
        entry.fillWithSample(
                "노을 지는 해변을 걷는 강아지",
                "https://cdn.example.com/samples/dog.png",
                Instant.parse("2026-07-08T10:00:00Z"));

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getPrompt()).isEqualTo("노을 지는 해변을 걷는 강아지");
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/samples/dog.png");
            softly.assertThat(entry.getSubmittedAt()).isEqualTo(Instant.parse("2026-07-08T10:00:00Z"));
            softly.assertThat(entry.isImageGenerated()).isTrue();
        });
    }

    @Test
    @DisplayName("이미 READY 상태면 뒤늦게 도착한 이미지 생성 완료를 무시한다.")
    void completeImageGeneration_이미_READY면_무시한다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");
        entry.fillWithSample(
                "샘플 프롬프트",
                "https://cdn.example.com/samples/sample.png",
                Instant.parse("2026-07-08T10:00:00Z"));

        // when
        entry.completeImageGeneration("https://cdn.example.com/late.png");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/samples/sample.png");
        });
    }

    @Test
    @DisplayName("이미 READY 상태면 뒤늦게 도착한 이미지 생성 실패를 무시한다.")
    void failImageGeneration_이미_READY면_무시한다() {
        // given
        PromptEntry entry = PromptEntry.waiting("player-id");
        entry.fillWithSample(
                "샘플 프롬프트",
                "https://cdn.example.com/samples/sample.png",
                Instant.parse("2026-07-08T10:00:00Z"));

        // when
        entry.failImageGeneration();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/samples/sample.png");
        });
    }
}
