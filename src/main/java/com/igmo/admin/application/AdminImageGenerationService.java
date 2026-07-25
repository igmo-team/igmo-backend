package com.igmo.admin.application;

import com.igmo.admin.exception.AdminImageGenerationBusyException;
import com.igmo.admin.exception.AdminImageGenerationConfigurationException;
import com.igmo.admin.exception.AdminImageGenerationFailedException;
import com.igmo.admin.exception.AdminImageStorageException;
import com.igmo.admin.exception.InvalidAdminImageGenerationRequestException;
import com.igmo.admin.infrastructure.AdminImageStorageClient;
import com.igmo.admin.web.dto.AdminImageGenerationOptionsResponse;
import com.igmo.admin.web.dto.AdminImageGenerationRequest;
import com.igmo.admin.web.dto.AdminImageGenerationResponse;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AdminImageGenerationService {

    private final ImageGenerator imageGenerator;
    private final AdminImageStorageClient adminImageStorageClient;
    private final List<String> allowedModels;
    private final List<String> allowedImageSizes;
    private final Semaphore executionSlots;

    public AdminImageGenerationService(
            ImageGenerator imageGenerator,
            AdminImageStorageClient adminImageStorageClient,
            @Value("${igmo.admin.image-generation.allowed-models:}") String allowedModels,
            @Value("${igmo.admin.image-generation.allowed-image-sizes:}") String allowedImageSizes,
            @Value("${igmo.admin.image-generation.max-concurrent-requests:1}") int maxConcurrentRequests
    ) {
        this.imageGenerator = imageGenerator;
        this.adminImageStorageClient = adminImageStorageClient;
        this.allowedModels = splitValues(allowedModels);
        this.allowedImageSizes = splitValues(allowedImageSizes);
        this.executionSlots = new Semaphore(maxConcurrentRequests);
    }

    public AdminImageGenerationOptionsResponse getOptions() {
        verifyConfiguration();
        return new AdminImageGenerationOptionsResponse(allowedModels, allowedImageSizes);
    }

    public AdminImageGenerationResponse generate(AdminImageGenerationRequest request) {
        verifyConfiguration();
        verifyAllowedOption(request.model(), allowedModels, "model");
        verifyAllowedOption(request.imageSize(), allowedImageSizes, "imageSize");
        adminImageStorageClient.validateConfiguration();
        if (!executionSlots.tryAcquire()) {
            throw new AdminImageGenerationBusyException();
        }

        long startedAt = System.nanoTime();
        try {
            GeneratedImage image = generateImage(request);
            String storageUri = storeImage(image);
            return new AdminImageGenerationResponse(
                    "data:%s;base64,%s".formatted(image.contentType(), Base64.getEncoder().encodeToString(image.data())),
                    storageUri,
                    request.model(),
                    request.imageSize(),
                    elapsedMillis(startedAt));
        } finally {
            executionSlots.release();
        }
    }

    private GeneratedImage generateImage(AdminImageGenerationRequest request) {
        try {
            return imageGenerator.generate(
                    new ImageGenerationRequest(request.prompt().trim(), request.model(), request.imageSize()));
        } catch (RuntimeException exception) {
            throw new AdminImageGenerationFailedException(exception);
        }
    }

    private List<String> splitValues(String values) {
        return Stream.of(values.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private String storeImage(GeneratedImage image) {
        try {
            return adminImageStorageClient.store(image.data(), image.contentType());
        } catch (RuntimeException exception) {
            throw new AdminImageStorageException(exception);
        }
    }

    private void verifyConfiguration() {
        if (allowedModels.isEmpty() || allowedImageSizes.isEmpty()) {
            throw new AdminImageGenerationConfigurationException();
        }
    }

    private void verifyAllowedOption(String value, List<String> allowedValues, String fieldName) {
        if (!allowedValues.contains(value)) {
            throw new InvalidAdminImageGenerationRequestException(fieldName);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
