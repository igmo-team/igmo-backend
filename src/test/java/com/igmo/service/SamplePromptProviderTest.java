package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.SamplePrompt;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SamplePromptProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("JSON 내용을 파싱해 샘플 프롬프트 목록을 반환한다.")
    void getAll_JSON을_파싱해_목록을_반환한다() {
        // given
        String json = """
                [
                  { "prompt": "테스트 프롬프트 1", "imageUrl": "https://cdn.example.com/samples/test-1.png" },
                  { "prompt": "테스트 프롬프트 2", "imageUrl": "https://cdn.example.com/samples/test-2.png" }
                ]
                """;

        // when
        List<SamplePrompt> samples = new SamplePromptProvider(objectMapper, json, "test").getAll();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(samples).hasSize(2);
            softly.assertThat(samples.get(0))
                    .isEqualTo(new SamplePrompt("테스트 프롬프트 1", "https://cdn.example.com/samples/test-1.png"));
            softly.assertThat(samples.get(1))
                    .isEqualTo(new SamplePrompt("테스트 프롬프트 2", "https://cdn.example.com/samples/test-2.png"));
        });
    }

    @Test
    @DisplayName("샘플이 비어 있으면 예외를 던진다.")
    void 비어_있으면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> new SamplePromptProvider(objectMapper, "[]", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("샘플 프롬프트가 비어 있습니다");
    }

    @Test
    @DisplayName("항목에 prompt나 imageUrl이 비어 있으면 예외를 던진다.")
    void 항목_필드가_비어_있으면_예외를_던진다() {
        // given
        String json = """
                [ { "prompt": "프롬프트만 있음", "imageUrl": "" } ]
                """;

        // when & then
        assertThatThrownBy(() -> new SamplePromptProvider(objectMapper, json, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompt 또는 imageUrl");
    }

    @Test
    @DisplayName("기본 생성자는 실제 샘플 리소스를 불러오고 모든 항목의 프롬프트와 이미지 URL이 채워져 있다.")
    void 기본_생성자는_실제_샘플_리소스를_불러온다() {
        // given
        SamplePromptProvider provider = new SamplePromptProvider(objectMapper);

        // when
        List<SamplePrompt> samples = provider.getAll();

        // then
        assertThat(samples).isNotEmpty();
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.prompt()).isNotBlank();
            assertThat(sample.imageUrl()).isNotBlank();
        });
    }
}
