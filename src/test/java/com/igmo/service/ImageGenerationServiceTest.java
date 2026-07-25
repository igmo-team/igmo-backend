package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import com.igmo.imagegeneration.exception.GeminiResponseException;
import com.igmo.imagegeneration.exception.ImageStorageException;
import com.igmo.monitoring.GameMetrics;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ImageGenerationServiceTest {

    private final ImageGenerator imageGenerator = mock(ImageGenerator.class);
    private final ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);
    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final ImageGenerationService imageGenerationService =
            new ImageGenerationService(imageGenerator, imageStorageClient, gameMetrics, Runnable::run, "gemini-image", "2K");
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
        when(imageGenerator.generate(new ImageGenerationRequest("고양이가 피아노를 치는 장면", "gemini-image", "2K")))
                .thenReturn(new GeneratedImage("image".getBytes(), "image/jpeg"));
        when(imageStorageClient.store("image".getBytes(), "image/jpeg"))
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
        verify(imageGenerator).generate(new ImageGenerationRequest("고양이가 피아노를 치는 장면", "gemini-image", "2K"));
        verify(imageStorageClient).store("image".getBytes(), "image/jpeg");
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
        when(imageGenerator.generate(new ImageGenerationRequest("실패 프롬프트", "gemini-image", "2K"))).thenThrow(exception);
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
        verify(imageGenerator).generate(new ImageGenerationRequest("실패 프롬프트", "gemini-image", "2K"));
        assertThat(lastLogMessage("이미지 생성 실패"))
                .contains("roomCode=ABCD", "playerId=player-1", "durationMs=")
                .contains("reason=Gemini 응답에 이미지 데이터가 없습니다.");
    }

    @Test
    @DisplayName("S3 저장 실패는 이미지 저장 예외로 감싸고 실패 콜백으로 전달한다.")
    void generate_s3저장실패면_저장예외를전달한다() {
        // given
        when(imageGenerator.generate(new ImageGenerationRequest("프롬프트", "gemini-image", "2K")))
                .thenReturn(new GeneratedImage("image".getBytes(), "image/jpeg"));
        when(imageStorageClient.store("image".getBytes(), "image/jpeg"))
                .thenThrow(new IllegalStateException("AccessDenied"));
        AtomicReference<Exception> capturedException = new AtomicReference<>();

        // when
        imageGenerationService.generate("ABCD", "player-1", "프롬프트", imageUrl -> { }, capturedException::set);

        // then
        assertThat(capturedException.get()).isInstanceOf(ImageStorageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private String lastLogMessage(String messagePrefix) {
        return imageGenerationLogAppender.list.stream()
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().startsWith(messagePrefix))
                .reduce((previous, current) -> current)
                .orElseThrow()
                .getFormattedMessage();
    }
}
