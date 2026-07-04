package com.igmo.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "IGMO API",
                version = "v1",
                description = "IGMO REST API 문서입니다. WebSocket 메시지는 별도 문서로 관리합니다."
        )
)
public class OpenApiConfig {
}
