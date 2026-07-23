package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.igmo.monitoring.GameMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3ImageStorageClientTest {

    private final GameMetrics gameMetrics = mock(GameMetrics.class);

    @Test
    @DisplayName("이미지를 S3에 저장하고 public base URL 기준 이미지 URL을 반환한다.")
    void store_uploadsImageToS3AndReturnsImageUrl() throws IOException {
        // given
        S3Client s3Client = mock(S3Client.class);
        S3ImageStorageClient client = new S3ImageStorageClient(
                s3Client,
                gameMetrics,
                "igmo-images",
                "ap-northeast-2",
                "/generated-images/",
                "https://cdn.example.com/images/"
        );
        byte[] image = "image".getBytes(StandardCharsets.UTF_8);

        // when
        String imageUrl = client.store(image, "image/png");

        // then
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("igmo-images");
        assertThat(request.key()).startsWith("generated-images/");
        assertThat(request.key()).endsWith(".png");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(image);
        assertThat(imageUrl).isEqualTo("https://cdn.example.com/images/" + request.key());
        verify(gameMetrics).recordImageUploadDuration(any());
    }

    @Test
    @DisplayName("JPEG 이미지는 jpg 확장자로 저장한다.")
    void store_usesJpgExtensionForJpegImage() {
        // given
        S3Client s3Client = mock(S3Client.class);
        S3ImageStorageClient client = new S3ImageStorageClient(
                s3Client,
                gameMetrics,
                "igmo-images",
                "ap-northeast-2",
                "generated-images",
                "https://cdn.example.com/images"
        );

        // when
        client.store("image".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        // then
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertThat(requestCaptor.getValue().key()).endsWith(".jpg");
        assertThat(requestCaptor.getValue().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("public base URL이 없으면 S3 객체 URL을 반환한다.")
    void store_returnsS3ObjectUrlWithoutPublicBaseUrl() {
        // given
        S3Client s3Client = mock(S3Client.class);
        S3ImageStorageClient client = new S3ImageStorageClient(
                s3Client,
                gameMetrics,
                "igmo-images",
                "ap-northeast-2",
                "generated images",
                ""
        );

        // when
        String imageUrl = client.store("image".getBytes(StandardCharsets.UTF_8), "image/png");

        // then
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertThat(imageUrl)
                .isEqualTo("https://igmo-images.s3.ap-northeast-2.amazonaws.com/"
                        + requestCaptor.getValue().key().replace(" ", "%20"));
    }

    @Test
    @DisplayName("S3 bucket이 설정되지 않으면 예외를 던진다.")
    void store_throwsExceptionWithoutBucket() {
        // given
        S3Client s3Client = mock(S3Client.class);
        S3ImageStorageClient client = new S3ImageStorageClient(
                s3Client,
                gameMetrics,
                "",
                "ap-northeast-2",
                "generated-images",
                ""
        );

        // when & then
        assertThatThrownBy(() -> client.store("image".getBytes(StandardCharsets.UTF_8), "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("S3 bucket is required for image storage.");
        verifyNoInteractions(s3Client);
    }
}
