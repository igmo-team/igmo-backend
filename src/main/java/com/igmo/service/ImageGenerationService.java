package com.igmo.service;

import com.igmo.service.exception.ImageStorageException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ImageGenerationService {

    private final ImageGenerationClient imageGenerationClient;
    private final Executor imageGenerationExecutor;

    public ImageGenerationService(
            ImageGenerationClient imageGenerationClient,
            @Qualifier("imageGenerationExecutor") Executor imageGenerationExecutor
    ) {
        this.imageGenerationClient = imageGenerationClient;
        this.imageGenerationExecutor = imageGenerationExecutor;
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
            String imageUrl = imageGenerationClient.generate(prompt);
            onSuccess.accept(imageUrl);
            log.info(
                    "이미지 생성 완료. roomCode={}, playerId={}, durationMs={}",
                    code,
                    playerId,
                    elapsedMillis(startedAt));
        } catch (Exception exception) {
            logGenerationFailure(code, playerId, startedAt, exception);
            onFailure.accept(exception);
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
