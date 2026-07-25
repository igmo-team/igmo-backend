package com.igmo.service;

import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import com.igmo.imagegeneration.exception.ImageStorageException;
import com.igmo.monitoring.GameMetrics;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ImageGenerationService {

    private final ImageGenerator imageGenerator;
    private final ImageStorageClient imageStorageClient;
    private final GameMetrics gameMetrics;
    private final Executor imageGenerationExecutor;
    private final String model;
    private final String imageSize;

    public ImageGenerationService(
            ImageGenerator imageGenerator,
            ImageStorageClient imageStorageClient,
            GameMetrics gameMetrics,
            @Qualifier("imageGenerationExecutor") Executor imageGenerationExecutor,
            @Value("${igmo.ai.gemini.model}") String model,
            @Value("${igmo.ai.gemini.image-size}") String imageSize
    ) {
        this.imageGenerator = imageGenerator;
        this.imageStorageClient = imageStorageClient;
        this.gameMetrics = gameMetrics;
        this.imageGenerationExecutor = imageGenerationExecutor;
        this.model = model;
        this.imageSize = imageSize;
    }

    public void generate(
            String code,
            String playerId,
            String prompt,
            Consumer<String> onSuccess,
            Consumer<Exception> onFailure
    ) {
        imageGenerationExecutor.execute(() -> runGeneration(code, playerId, prompt, onSuccess, onFailure));
    }

    private void runGeneration(
            String code,
            String playerId,
            String prompt,
            Consumer<String> onSuccess,
            Consumer<Exception> onFailure
    ) {
        long startedAt = System.nanoTime();
        try {
            String imageUrl = generateAndStore(prompt);
            onSuccess.accept(imageUrl);
            log.info(
                    "이미지 생성 완료. roomCode={}, playerId={}, durationMs={}",
                    code,
                    playerId,
                    elapsedMillis(startedAt));
        } catch (Exception exception) {
            gameMetrics.incrementImageGenerationFailure();
            logGenerationFailure(code, playerId, startedAt, exception);
            onFailure.accept(exception);
        } finally {
            gameMetrics.recordImageGenerationDuration(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private String generateAndStore(String prompt) {
        GeneratedImage image = imageGenerator.generate(new ImageGenerationRequest(prompt, model, imageSize));
        try {
            return imageStorageClient.store(image.data(), image.contentType());
        } catch (Exception exception) {
            throw new ImageStorageException(exception);
        }
    }

    private void logGenerationFailure(String code, String playerId, long startedAt, Exception exception) {
        if (exception instanceof ImageStorageException) {
            log.warn(
                    "S3 이미지 저장 실패. roomCode={}, playerId={}, reason={}, durationMs={}",
                    code,
                    playerId,
                    exception.getMessage(),
                    elapsedMillis(startedAt),
                    exception);
            return;
        }
        log.warn(
                "이미지 생성 실패. roomCode={}, playerId={}, reason={}, durationMs={}",
                code,
                playerId,
                exception.getMessage(),
                elapsedMillis(startedAt),
                exception);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
