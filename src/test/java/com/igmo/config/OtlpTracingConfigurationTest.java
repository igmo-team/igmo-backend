package com.igmo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.support.AbstractNonWebSpringBootTest;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class OtlpTracingConfigurationTest extends AbstractNonWebSpringBootTest {

    private static final String AUTHORIZATION = "Basic dGVzdC11c2VyOnRlc3QtdG9rZW4=";
    private static final CountDownLatch REQUEST_RECEIVED = new CountDownLatch(1);
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<byte[]> REQUEST_BODY = new AtomicReference<>();
    private static HttpServer server;

    @Autowired
    private Tracer tracer;

    @DynamicPropertySource
    static void registerTracingProperties(DynamicPropertyRegistry registry) {
        server = startServer();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
        registry.add("IGMO_TRACING_ENABLED", () -> "true");
        registry.add("IGMO_TRACING_SAMPLING_PROBABILITY", () -> "1.0");
        registry.add("IGMO_TRACING_OTLP_ENDPOINT", () -> endpoint);
        registry.add("IGMO_TRACING_OTLP_AUTHORIZATION", () -> AUTHORIZATION);
        registry.add("OTEL_SERVICE_NAME", () -> "igmo-tracing-test");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("활성화된 트레이싱은 /v1/traces로 Authorization 헤더와 함께 span을 전송한다.")
    void exportsSpanToConfiguredOtlpEndpoint() throws InterruptedException {
        // given
        Span span = tracer.nextSpan().name("otlp-export-test").start();

        // when
        span.end();

        // then
        assertThat(REQUEST_RECEIVED.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(REQUEST_PATH).hasValue("/v1/traces");
        assertThat(REQUEST_AUTHORIZATION).hasValue(AUTHORIZATION);
        assertThat(REQUEST_BODY).hasValueSatisfying(body -> assertThat(body).isNotEmpty());
    }

    private static HttpServer startServer() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/", exchange -> {
                REQUEST_PATH.set(exchange.getRequestURI().getPath());
                REQUEST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                REQUEST_BODY.set(exchange.getRequestBody().readAllBytes());
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(response);
                }
                REQUEST_RECEIVED.countDown();
            });
            httpServer.start();
            return httpServer;
        } catch (IOException exception) {
            throw new IllegalStateException("OTLP 테스트 서버를 시작할 수 없습니다.", exception);
        }
    }
}
