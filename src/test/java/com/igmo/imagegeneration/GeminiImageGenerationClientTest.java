package com.igmo.imagegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.imagegeneration.exception.GeminiRequestException;
import com.igmo.imagegeneration.exception.GeminiResponseException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    @DisplayName("선택한 모델과 크기로 Gemini Interactions 요청을 보내고 이미지 바이트를 반환한다.")
    void generate_sendsInteractionsRequestAndReturnsImage() throws Exception {
        // given
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = startServer(requestBody);
        GeminiImageGenerationClient client = createClient();

        // when
        GeneratedImage generatedImage = client.generate(new ImageGenerationRequest("동굴 벽화 스타일의 바나나", "gemini-image", "2K"));

        // then
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("gemini-image");
        assertThat(body.path("input").get(0).path("text").asText()).isEqualTo("동굴 벽화 스타일의 바나나");
        assertThat(body.path("response_format").path("image_size").asText()).isEqualTo("2K");
        assertThat(generatedImage.data()).isEqualTo("image".getBytes(StandardCharsets.UTF_8));
        assertThat(generatedImage.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("모델 출력이 아닌 step은 건너뛰고 모델 출력 이미지를 반환한다.")
    void generate_skipsNonModelOutputSteps() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), 200, """
                {"steps":[
                  {"type":"tool_output","content":[{"type":"image","data":"d3Jvbmc=","mime_type":"image/jpeg"}]},
                  {"type":"model_output","content":[{"type":"image","data":"cmlnaHQ=","mime_type":"image/jpeg"}]}
                ]}
                """);

        // when
        GeneratedImage generatedImage = createClient().generate(new ImageGenerationRequest("프롬프트", "gemini-image", "1K"));

        // then
        assertThat(generatedImage.data()).isEqualTo("right".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("텍스트만 응답하면 이미지 응답 예외를 던진다.")
    void generate_throwsWhenResponseContainsOnlyText() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), 200, """
                {"steps":[{"type":"model_output","content":[{"type":"text","text":"image omitted"}]}]}
                """);

        // when & then
        assertThatThrownBy(() -> createClient().generate(new ImageGenerationRequest("프롬프트", "gemini-image", "1K")))
                .isInstanceOf(GeminiResponseException.class)
                .hasMessage("Gemini 응답이 이미지 대신 텍스트입니다.");
    }

    @Test
    @DisplayName("이미지 바이트가 없으면 이미지 응답 예외를 던진다.")
    void generate_throwsWhenImageDataIsMissing() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), 200, """
                {"steps":[{"type":"model_output","content":[{"type":"image","mime_type":"image/jpeg"}]}]}
                """);

        // when & then
        assertThatThrownBy(() -> createClient().generate(new ImageGenerationRequest("프롬프트", "gemini-image", "1K")))
                .isInstanceOf(GeminiResponseException.class)
                .hasMessage("Gemini 응답에 이미지 바이트 데이터가 없습니다.");
    }

    @Test
    @DisplayName("Gemini 비성공 응답은 요청 예외와 HTTP 상태를 반환한다.")
    void generate_throwsWhenGeminiReturnsError() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), 400, "{\"error\":{\"message\":\"unsupported image_size\"}}");

        // when & then
        assertThatThrownBy(() -> createClient().generate(new ImageGenerationRequest("프롬프트", "gemini-image", "1K")))
                .isInstanceOfSatisfying(GeminiRequestException.class, exception ->
                        assertThat(exception.getHttpStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("Gemini 응답이 요청 timeout을 넘으면 요청 예외를 던진다.")
    void generate_응답이_요청_timeout을_넘으면_요청_예외를_던진다() throws Exception {
        // given
        server = startServer(new AtomicReference<>(), 200, "{}", Duration.ofMillis(200));
        GeminiImageGenerationClient client = createClient(Duration.ofMillis(50));

        // when & then
        assertThatThrownBy(() -> client.generate(new ImageGenerationRequest("프롬프트", "gemini-image", "1K")))
                .isInstanceOf(GeminiRequestException.class)
                .hasMessage("Gemini 이미지 생성 요청에 실패했습니다.");
    }

    private GeminiImageGenerationClient createClient() {
        return createClient(Duration.ofSeconds(15));
    }

    private GeminiImageGenerationClient createClient(Duration requestTimeout) {
        return new GeminiImageGenerationClient(
                objectMapper,
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1beta/interactions"),
                "api-key",
                requestTimeout);
    }

    private HttpServer startServer(AtomicReference<String> requestBody) throws IOException {
        return startServer(requestBody, 200, """
                {"steps":[{"type":"model_output","content":[{"type":"image","data":"aW1hZ2U=","mime_type":"image/jpeg"}]}]}
                """);
    }

    private HttpServer startServer(AtomicReference<String> requestBody, int statusCode, String responseBody)
            throws IOException {
        return startServer(requestBody, statusCode, responseBody, Duration.ZERO);
    }

    private HttpServer startServer(
            AtomicReference<String> requestBody,
            int statusCode,
            String responseBody,
            Duration responseDelay
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/v1beta/interactions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(responseDelay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
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
