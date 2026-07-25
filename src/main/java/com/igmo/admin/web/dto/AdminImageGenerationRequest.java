package com.igmo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminImageGenerationRequest(
        @NotBlank(message = "프롬프트를 입력해주세요.")
        @Size(max = 2_000, message = "프롬프트는 2,000자 이하여야 합니다.")
        String prompt,
        @NotBlank(message = "모델을 선택해주세요.")
        String model,
        @NotBlank(message = "이미지 크기를 선택해주세요.")
        String imageSize
) {
}
