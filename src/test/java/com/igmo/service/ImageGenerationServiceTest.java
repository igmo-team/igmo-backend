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
import com.igmo.imagegeneration.exception.GeminiRequestException;
import com.igmo.imagegeneration.exception.GeminiResponseException;
import com.igmo.imagegeneration.exception.ImageStorageException;
import com.igmo.monitoring.GameMetrics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        ILoggingEvent logEvent = lastLogEvent("image_generation_completed");
        assertThat(logEvent.getFormattedMessage()).matches("duration=\\d+ms");
        assertThat(keyValues(logEvent))
                .containsEntry("event", "image_generation_completed")
                .containsEntry("roomCode", "ABCD")
                .containsEntry("playerId", "player-1")
                .containsKey("durationMs");
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
        ILoggingEvent logEvent = lastLogEvent("gemini_image_generation_failed");
        assertThat(logEvent.getFormattedMessage()).isEqualTo("Gemini 응답에 이미지 데이터가 없습니다.");
        assertThat(keyValues(logEvent))
                .containsEntry("event", "gemini_image_generation_failed")
                .containsEntry("prompt", "실패 프롬프트")
                .containsEntry("roomCode", "ABCD")
                .containsEntry("playerId", "player-1")
                .containsKey("durationMs")
                .containsEntry("httpStatus", 200)
                .containsEntry("providerStatus", null)
                .containsEntry("providerMessage", null);
        assertThat(logEvent.getThrowableProxy().getClassName()).isEqualTo(exception.getClass().getName());
    }

    @Test
    @DisplayName("Gemini 요청 실패 로그에 실제 요청 프롬프트와 provider 오류 정보를 남긴다.")
    void generate_gemini요청실패면_프롬프트와_provider오류를_로그로_남긴다() {
        // given
        GeminiRequestException exception = new GeminiRequestException(
                400,
                "gemini-image",
                "2K",
                "INVALID_ARGUMENT",
                "Request contains an invalid argument.");
        String finalPrompt = "최종 Gemini 프롬프트";
        when(imageGenerator.generate(new ImageGenerationRequest(finalPrompt, "gemini-image", "2K")))
                .thenThrow(exception);

        // when
        imageGenerationService.generate(
                "ABCD",
                "player-1",
                finalPrompt,
                imageUrl -> { throw new AssertionError(imageUrl); },
                ignored -> { });

        // then
        ILoggingEvent logEvent = lastLogEvent("gemini_image_generation_failed");
        assertThat(keyValues(logEvent))
                .containsEntry("event", "gemini_image_generation_failed")
                .containsEntry("prompt", finalPrompt)
                .containsEntry("roomCode", "ABCD")
                .containsEntry("playerId", "player-1")
                .containsKey("durationMs")
                .containsEntry("httpStatus", 400)
                .containsEntry("providerStatus", "INVALID_ARGUMENT")
                .containsEntry("providerMessage", "Request contains an invalid argument.");
        assertThat(logEvent.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("provider 오류 정보가 없으면 원인 예외와 메시지를 실패 로그에 남긴다.")
    void generate_provider오류가없으면_원인예외와_메시지를_로그로_남긴다() {
        // given
        IllegalStateException cause = new IllegalStateException("connection timed out");
        GeminiRequestException exception = new GeminiRequestException(
                "Gemini 이미지 생성 요청에 실패했습니다.",
                "gemini-image",
                "2K",
                cause);
        String finalPrompt = "전송 실패 프롬프트";
        when(imageGenerator.generate(new ImageGenerationRequest(finalPrompt, "gemini-image", "2K")))
                .thenThrow(exception);

        // when
        imageGenerationService.generate(
                "ABCD",
                "player-1",
                finalPrompt,
                imageUrl -> { throw new AssertionError(imageUrl); },
                ignored -> { });

        // then
        ILoggingEvent logEvent = lastLogEvent("gemini_image_generation_failed");
        assertThat(logEvent.getFormattedMessage()).isEqualTo(exception.getMessage());
        assertThat(logEvent.getThrowableProxy().getClassName()).isEqualTo(exception.getClass().getName());
        assertThat(logEvent.getThrowableProxy().getCause().getClassName()).isEqualTo(cause.getClass().getName());
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
        ILoggingEvent logEvent = lastLogEvent("s3_image_upload_failed");
        assertThat(logEvent.getFormattedMessage()).isEqualTo("S3 이미지 저장에 실패했습니다.");
        assertThat(keyValues(logEvent))
                .containsEntry("event", "s3_image_upload_failed")
                .containsEntry("roomCode", "ABCD")
                .containsEntry("playerId", "player-1")
                .containsEntry("exceptionType", "ImageStorageException")
                .containsKey("durationMs");
    }

    private ILoggingEvent lastLogEvent(String event) {
        return imageGenerationLogAppender.list.stream()
                .filter(loggingEvent -> event.equals(keyValues(loggingEvent).get("event")))
                .reduce((previous, current) -> current)
                .orElseThrow();
    }

    private Map<String, Object> keyValues(ILoggingEvent logEvent) {
        Map<String, Object> keyValues = new HashMap<>();
        logEvent.getKeyValuePairs().forEach(pair -> keyValues.put(pair.key, pair.value));
        return keyValues;
    }
}
