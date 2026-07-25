package com.igmo.admin.infrastructure;

import com.igmo.admin.exception.AdminImageStorageConfigurationException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@Slf4j
public class AdminImageStorageClient {

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;

    public AdminImageStorageClient(
            S3Client s3Client,
            @Value("${igmo.admin.image-generation.storage.s3.bucket:}") String bucket,
            @Value("${igmo.admin.image-generation.storage.s3.key-prefix:generated-admin-images}") String keyPrefix
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.keyPrefix = normalizeKeyPrefix(keyPrefix);
    }

    public String store(byte[] image, String contentType) {
        validateConfiguration();

        String key = keyPrefix + "/" + UUID.randomUUID() + extension(contentType);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(image));
        } catch (RuntimeException exception) {
            log.warn("관리자 이미지 S3 저장 실패. bucket={}, key={}", bucket, key, exception);
            throw exception;
        }
        return "s3://%s/%s".formatted(bucket, key);
    }

    public void validateConfiguration() {
        if (!StringUtils.hasText(bucket)) {
            throw new AdminImageStorageConfigurationException();
        }
    }

    private String extension(String contentType) {
        return "image/jpeg".equals(contentType) ? ".jpg" : ".png";
    }

    private String normalizeKeyPrefix(String keyPrefix) {
        return keyPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
