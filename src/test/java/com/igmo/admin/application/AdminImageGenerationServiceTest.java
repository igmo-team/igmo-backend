package com.igmo.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.igmo.admin.exception.AdminImageStorageConfigurationException;
import com.igmo.admin.exception.AdminImageStorageException;
import com.igmo.admin.exception.InvalidAdminImageGenerationRequestException;
import com.igmo.admin.infrastructure.AdminImageStorageClient;
import com.igmo.admin.web.dto.AdminImageGenerationRequest;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminImageGenerationServiceTest {

    private final ImageGenerator imageGenerator = mock(ImageGenerator.class);
    private final AdminImageStorageClient adminImageStorageClient = mock(AdminImageStorageClient.class);

    @Test
    @DisplayName("허용된 모델과 크기로 생성하면 S3 URL 대신 이미지 data URL을 반환한다.")
    void generate_허용된옵션이면_dataUrl을_반환한다() {
        // given
        AdminImageGenerationService service = createService();
        AdminImageGenerationRequest request = new AdminImageGenerationRequest("  토끼가 달을 보는 장면  ", "gemini-image", "2K");
        given(imageGenerator.generate(
                new ImageGenerationRequest("토끼가 달을 보는 장면", "gemini-image", "2K")))
                .willReturn(new GeneratedImage("image".getBytes(StandardCharsets.UTF_8), "image/jpeg"));
        given(adminImageStorageClient.store("image".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
                .willReturn("s3://admin-bucket/generated-admin-images/image.jpg");

        // when
        var response = service.generate(request);

        // then
        assertThat(response.imageDataUrl()).isEqualTo("data:image/jpeg;base64,aW1hZ2U=");
        assertThat(response.storageUri()).isEqualTo("s3://admin-bucket/generated-admin-images/image.jpg");
        assertThat(response.model()).isEqualTo("gemini-image");
        assertThat(response.imageSize()).isEqualTo("2K");
        verify(imageGenerator).generate(new ImageGenerationRequest("토끼가 달을 보는 장면", "gemini-image", "2K"));
        verify(adminImageStorageClient).store("image".getBytes(StandardCharsets.UTF_8), "image/jpeg");
    }

    @Test
    @DisplayName("허용 목록에 없는 모델이면 요청하지 않고 예외를 던진다.")
    void generate_허용되지않은모델이면_예외를던진다() {
        // given
        AdminImageGenerationService service = createService();
        AdminImageGenerationRequest request = new AdminImageGenerationRequest("토끼", "unlisted-model", "2K");

        // when & then
        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(InvalidAdminImageGenerationRequestException.class)
                .hasMessage("허용되지 않은 model 값입니다.");
    }

    @Test
    @DisplayName("관리자 S3 설정이 없으면 Gemini 요청 전에 예외를 던진다.")
    void generate_s3설정없으면_Gemini요청전에예외를던진다() {
        // given
        AdminImageStorageClient unconfiguredStorageClient = mock(AdminImageStorageClient.class);
        AdminImageGenerationService service = new AdminImageGenerationService(
                imageGenerator, unconfiguredStorageClient, "gemini-image", "2K", 1);
        AdminImageGenerationRequest request = new AdminImageGenerationRequest("토끼", "gemini-image", "2K");
        doThrow(new AdminImageStorageConfigurationException())
                .when(unconfiguredStorageClient)
                .validateConfiguration();

        // when & then
        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(AdminImageStorageConfigurationException.class)
                .hasMessage("관리자 이미지 저장용 S3 bucket이 설정되지 않았습니다.");
        verifyNoInteractions(imageGenerator);
    }

    @Test
    @DisplayName("Gemini 생성 뒤 S3 저장이 실패하면 저장 실패 예외를 던진다.")
    void generate_s3저장실패면_저장실패예외를던진다() {
        // given
        AdminImageGenerationService service = createService();
        AdminImageGenerationRequest request = new AdminImageGenerationRequest("토끼", "gemini-image", "2K");
        byte[] image = "image".getBytes(StandardCharsets.UTF_8);
        given(imageGenerator.generate(new ImageGenerationRequest("토끼", "gemini-image", "2K")))
                .willReturn(new GeneratedImage(image, "image/jpeg"));
        given(adminImageStorageClient.store(image, "image/jpeg"))
                .willThrow(new IllegalStateException("AccessDenied"));

        // when & then
        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(AdminImageStorageException.class)
                .hasMessage("관리자 이미지의 S3 저장에 실패했습니다.")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private AdminImageGenerationService createService() {
        return new AdminImageGenerationService(
                imageGenerator, adminImageStorageClient, "gemini-image,gemini-image-pro", "1K,2K", 1);
    }
}
