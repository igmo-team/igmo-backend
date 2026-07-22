package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.service.exception.GeminiRequestException;
import com.igmo.service.exception.GeminiResponseException;
import com.igmo.service.exception.ImageStorageException;
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
        AtomicReference<byte[]> storedImage = new AtomicReference<>();
        AtomicReference<String> storedContentType = new AtomicReference<>();
        server = startServer(requestBody);
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                (image, contentType) -> {
                    storedImage.set(image);
                    storedContentType.set(contentType);
                    return "https://cdn.example.com/generated-images/prompt-1.png";
                },
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
        assertThat(body.path("system_instruction").asText())
                .contains("너는 이미지 생성 전용 모델이다.")
                .contains("응답으로 이미지 정확히 1장만 생성한다.")
                .contains("대화형 텍스트는 출력하지 않는다.");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("image");
        assertThat(body.path("response_format").path("mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(body.path("response_format").path("image_size").asText()).isEqualTo("2K");
        assertThat(storedImage.get()).isEqualTo("image".getBytes(StandardCharsets.UTF_8));
        assertThat(storedContentType.get()).isEqualTo("image/jpeg");
        assertThat(imageUrl).isEqualTo("https://cdn.example.com/generated-images/prompt-1.png");
    }

    @Test
    @DisplayName("이미지 content 블록이 없으면 Gemini 응답 예외를 던진다.")
    void generate_throwsExceptionWithoutImageContent() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), 200, """
                {"steps":[{"type":"model_output","content":[{"type":"text","text":"image omitted"}]}]}
                """);
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                (image, contentType) -> "https://cdn.example.com/generated-images/prompt-1.png",
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                "gemini-3.1-flash-image",
                "2K"
        );

        // when & then
        assertThatThrownBy(() -> client.generate("동굴 벽화 스타일의 바나나"))
                .isInstanceOf(GeminiResponseException.class)
                .hasMessage("Gemini 응답에 이미지 데이터가 없습니다.");
    }

    @Test
    @DisplayName("모델 출력이 아닌 step은 건너뛰고 모델 출력의 이미지 content를 저장한다.")
    void generate_skipsNonModelOutputStepsAndStoresModelOutputImage() throws Exception {
        // given
        AtomicReference<byte[]> storedImage = new AtomicReference<>();
        server = startServer(new AtomicReference<>(), 200, """
                {
                  "steps": [
                    {
                      "type": "tool_output",
                      "content": [
                        {"type": "image", "data": "d3JvbmctaW1hZ2U=", "mime_type": "image/jpeg"}
                      ]
                    },
                    {
                      "type": "model_output",
                      "content": [
                        {"type": "image", "data": "cmlnaHQtaW1hZ2U=", "mime_type": "image/jpeg"}
                      ]
                    }
                  ]
                }
                """);
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                (image, contentType) -> {
                    storedImage.set(image);
                    return "https://cdn.example.com/generated-images/prompt-1.png";
                },
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                "gemini-3.1-flash-image",
                "2K"
        );

        // when
        String imageUrl = client.generate("동굴 벽화 스타일의 바나나");

        // then
        assertThat(storedImage.get()).isEqualTo("right-image".getBytes(StandardCharsets.UTF_8));
        assertThat(imageUrl).isEqualTo("https://cdn.example.com/generated-images/prompt-1.png");
    }

    @Test
    @DisplayName("이미지 생성 API 실패 시 Gemini 요청 예외와 HTTP 상태를 반환한다.")
    void generate_throwsGeminiRequestExceptionWhenApiFails() throws Exception {
        // given
        String responseBody = "{\"error\":{\"message\":\"unsupported image_size\"}}";
        server = startServer(new AtomicReference<>(), 400, responseBody);
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                (image, contentType) -> "https://cdn.example.com/generated-images/prompt-1.png",
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                "gemini-3.1-flash-image",
                "2K"
        );

        // when & then
        assertThatThrownBy(() -> client.generate("동굴 벽화 스타일의 바나나"))
                .isInstanceOfSatisfying(GeminiRequestException.class, exception ->
                        assertThat(exception.getHttpStatus()).isEqualTo(400))
                .hasMessage("Gemini 이미지 생성 요청에 실패했습니다. status=400");
    }

    @Test
    @DisplayName("이미지 저장에 실패하면 이미지 저장 예외를 던진다.")
    void generate_throwsImageStorageExceptionWhenStorageFails() throws Exception {
        // given
        server = startServer(new AtomicReference<>());
        GeminiImageGenerationClient client = new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                (image, contentType) -> {
                    throw new IllegalStateException("S3 unavailable");
                },
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                "gemini-3.1-flash-image",
                "2K"
        );

        // when & then
        assertThatThrownBy(() -> client.generate("동굴 벽화 스타일의 바나나"))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("S3 이미지 저장에 실패했습니다.")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private HttpServer startServer(AtomicReference<String> requestBody) throws IOException {
        return startServer(requestBody, 200, """
                {"steps":[{"type":"model_output","content":[{"type":"image","data":"aW1hZ2U=","mime_type":"image/jpeg"}]}]}
                """);
    }

    private HttpServer startServer(AtomicReference<String> requestBody, int statusCode, String responseBody)
            throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/v1beta/interactions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        httpServer.start();
        return httpServer;
    }
}
