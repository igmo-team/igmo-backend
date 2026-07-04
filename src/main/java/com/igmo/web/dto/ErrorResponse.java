package com.igmo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "에러 응답")
public record ErrorResponse(
        @Schema(description = "에러 메시지", example = "방을 찾을 수 없습니다.")
        String message
) {
}
