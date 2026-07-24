package com.igmo.admin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.igmo.admin.exception.AdminImageStorageConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class AdminImageStorageClientTest {

    @Test
    @DisplayName("관리자 이미지를 전용 버킷에 저장하고 S3 URI를 반환한다.")
    void store_관리자전용버킷에저장하고_s3Uri를반환한다() throws IOException {
        // given
        S3Client s3Client = mock(S3Client.class);
        AdminImageStorageClient client = new AdminImageStorageClient(
                s3Client, "igmo-admin-images", "/generated-admin-images/");
        byte[] image = "image".getBytes(StandardCharsets.UTF_8);

        // when
        String storageUri = client.store(image, "image/jpeg");

        // then
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("igmo-admin-images");
        assertThat(request.key()).startsWith("generated-admin-images/");
        assertThat(request.key()).endsWith(".jpg");
        assertThat(request.contentType()).isEqualTo("image/jpeg");
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(image);
        assertThat(storageUri).isEqualTo("s3://igmo-admin-images/" + request.key());
    }

    @Test
    @DisplayName("관리자 버킷이 설정되지 않으면 S3 요청을 보내지 않는다.")
    void store_버킷없으면_예외를던진다() {
        // given
        S3Client s3Client = mock(S3Client.class);
        AdminImageStorageClient client = new AdminImageStorageClient(s3Client, "", "generated-admin-images");

        // when & then
        assertThatThrownBy(() -> client.store("image".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
                .isInstanceOf(AdminImageStorageConfigurationException.class)
                .hasMessage("관리자 이미지 저장용 S3 bucket이 설정되지 않았습니다.");
        verifyNoInteractions(s3Client);
    }
}
