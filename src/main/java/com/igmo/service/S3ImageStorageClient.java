package com.igmo.service;

import com.igmo.monitoring.GameMetrics;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3ImageStorageClient implements ImageStorageClient {

    private final S3Client s3Client;
    private final GameMetrics gameMetrics;
    private final String bucket;
    private final String region;
    private final String keyPrefix;
    private final String publicBaseUrl;

    public S3ImageStorageClient(
            S3Client s3Client,
            GameMetrics gameMetrics,
            @Value("${igmo.image-storage.s3.bucket}") String bucket,
            @Value("${igmo.image-storage.s3.region}") String region,
            @Value("${igmo.image-storage.s3.key-prefix}") String keyPrefix,
            @Value("${igmo.image-storage.s3.public-base-url}") String publicBaseUrl
    ) {
        this.s3Client = s3Client;
        this.gameMetrics = gameMetrics;
        this.bucket = bucket;
        this.region = region;
        this.keyPrefix = normalizeKeyPrefix(keyPrefix);
        this.publicBaseUrl = normalizePublicBaseUrl(publicBaseUrl);
    }

    @Override
    public String store(byte[] image, String contentType) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("S3 bucket is required for image storage.");
        }

        String key = createObjectKey(contentType);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        long startedAt = System.nanoTime();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(image));
        } finally {
            gameMetrics.recordImageUploadDuration(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        return buildImageUrl(key);
    }

    private String createObjectKey(String contentType) {
        return keyPrefix + "/" + UUID.randomUUID() + extension(contentType);
    }

    private String extension(String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return ".jpg";
        }
        return ".png";
    }

    private String buildImageUrl(String key) {
        String encodedKey = encodeObjectKey(key);
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl + "/" + encodedKey;
        }
        return "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, region, encodedKey);
    }

    private String encodeObjectKey(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private String normalizeKeyPrefix(String keyPrefix) {
        if (!StringUtils.hasText(keyPrefix)) {
            return "generated-images";
        }
        return keyPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String normalizePublicBaseUrl(String publicBaseUrl) {
        if (!StringUtils.hasText(publicBaseUrl)) {
            return "";
        }
        return publicBaseUrl.replaceAll("/+$", "");
    }
}
