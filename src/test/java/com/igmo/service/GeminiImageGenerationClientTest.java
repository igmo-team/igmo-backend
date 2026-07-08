package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiImageGenerationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("이미지 생성 요청을 Gemini Interactions REST 형식으로 보낸다.")
    void generate_sendsInteractionsRestRequest() throws Exception {
        // given
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = startServer(requestBody);
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                "gemini-3.1-flash-image",
                "2K"
        );

        // when
        String imageUrl = client.generate("동굴 벽화 스타일의 바나나");

        // then
        JsonNode body = objectMapper.readTree(requestBody.get());
        JsonNode input = body.path("input");
        assertThat(body.path("model").asText()).isEqualTo("gemini-3.1-flash-image");
        assertThat(input).hasSize(1);
        assertThat(input.get(0).path("type").asText()).isEqualTo("text");
        assertThat(input.get(0).path("text").asText()).isEqualTo("동굴 벽화 스타일의 바나나");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("image");
        assertThat(body.path("response_format").path("mime_type").asText()).isEqualTo("image/png");
        assertThat(body.path("response_format").path("image_size").asText()).isEqualTo("2K");
        assertThat(imageUrl).isEqualTo("data:image/png;base64,aW1hZ2U=");
    }

    @Test
    @DisplayName("공식 output_image 필드가 없으면 예외를 던진다.")
    void generate_throwsExceptionWithoutOfficialOutputImage() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), "{\"outputImage\":{\"data\":\"aW1hZ2U=\"}}");
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                "gemini-3.1-flash-image",
                "2K"
        );

        // when & then
        assertThatThrownBy(() -> client.generate("동굴 벽화 스타일의 바나나"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Image generation failed.")
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Image generation response does not contain output image data.");
    }

    private HttpServer startServer(AtomicReference<String> requestBody) throws IOException {
        return startServer(requestBody, "{\"output_image\":{\"data\":\"aW1hZ2U=\"}}");
    }

    private HttpServer startServer(AtomicReference<String> requestBody, String responseBody) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/v1beta/interactions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        httpServer.start();
        return httpServer;
    }
}
