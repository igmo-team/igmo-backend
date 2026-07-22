package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.igmo.service.exception.GeminiResponseException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ImageGenerationServiceTest {

    private final ImageGenerationClient imageGenerationClient = mock(ImageGenerationClient.class);
    private final ImageGenerationService imageGenerationService =
            new ImageGenerationService(imageGenerationClient, Runnable::run);
    private final Logger imageGenerationLogger = (Logger) LoggerFactory.getLogger(ImageGenerationService.class);
    private ListAppender<ILoggingEvent> imageGenerationLogAppender;

    @BeforeEach
    void 이미지_생성_로그_appender를_연결한다() {
        imageGenerationLogAppender = new ListAppender<>();
        imageGenerationLogAppender.start();
        imageGenerationLogger.addAppender(imageGenerationLogAppender);
    }

    @AfterEach
    void 이미지_생성_로그_appender를_제거한다() {
        imageGenerationLogger.detachAppender(imageGenerationLogAppender);
    }

    @Test
    @DisplayName("이미지 생성에 성공하면 생성 URL을 성공 콜백으로 전달한다.")
    void generate_성공하면_이미지_URL을_성공_콜백으로_전달한다() {
        // given
        when(imageGenerationClient.generate("고양이가 피아노를 치는 장면"))
                .thenReturn("https://cdn.example.com/image.png");
        AtomicReference<String> generatedImageUrl = new AtomicReference<>();

        // when
        imageGenerationService.generate(
                "ABCD",
                "player-1",
                "고양이가 피아노를 치는 장면",
                generatedImageUrl::set,
                exception -> { throw new AssertionError(exception); });

        // then
        assertThat(generatedImageUrl).hasValue("https://cdn.example.com/image.png");
        verify(imageGenerationClient).generate("고양이가 피아노를 치는 장면");
        assertThat(lastLogMessage("이미지 생성 완료"))
                .contains("roomCode=ABCD", "playerId=player-1", "durationMs=");
    }

    @Test
    @DisplayName("이미지 생성에 실패하면 예외를 실패 콜백으로 전달한다.")
    void generate_실패하면_예외를_실패_콜백으로_전달한다() {
        // given
        GeminiResponseException exception = new GeminiResponseException(
                "Gemini 응답에 이미지 데이터가 없습니다.",
                List.of("text"),
                200,
                "gemini-3.1-flash-image",
                "2K");
        when(imageGenerationClient.generate("실패 프롬프트")).thenThrow(exception);
        AtomicReference<Exception> capturedException = new AtomicReference<>();

        // when
        imageGenerationService.generate(
                "ABCD",
                "player-1",
                "실패 프롬프트",
                imageUrl -> { throw new AssertionError(imageUrl); },
                capturedException::set);

        // then
        assertThat(capturedException).hasValue(exception);
        verify(imageGenerationClient).generate("실패 프롬프트");
        assertThat(lastLogMessage("이미지 생성 실패"))
                .contains("roomCode=ABCD", "playerId=player-1", "durationMs=")
                .contains("reason=Gemini 응답에 이미지 데이터가 없습니다.");
    }

    private String lastLogMessage(String messagePrefix) {
        return imageGenerationLogAppender.list.stream()
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().startsWith(messagePrefix))
                .reduce((previous, current) -> current)
                .orElseThrow()
                .getFormattedMessage();
    }
}
