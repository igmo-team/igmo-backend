package com.igmo.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestLoggingFilterTest {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    @DisplayName("요청 ID가 없으면 새로 생성하고 응답 헤더와 MDC에 저장한다.")
    void generateRequestIdWhenHeaderIsMissing() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                requestIdInChain.set(MDC.get(REQUEST_ID_MDC_KEY));

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader(REQUEST_ID_HEADER)).isNotBlank();
        assertThat(requestIdInChain.get()).isEqualTo(response.getHeader(REQUEST_ID_HEADER));
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("요청 ID 헤더가 있으면 기존 값을 응답 헤더와 MDC에 사용한다.")
    void useIncomingRequestIdWhenHeaderIsPresent() throws Exception {
        // given
        String incomingRequestId = "client-request-id-1";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader(REQUEST_ID_HEADER, incomingRequestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                requestIdInChain.set(MDC.get(REQUEST_ID_MDC_KEY));

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader(REQUEST_ID_HEADER)).isEqualTo(incomingRequestId);
        assertThat(requestIdInChain.get()).isEqualTo(incomingRequestId);
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("요청 ID 헤더가 너무 길면 새 요청 ID를 생성한다.")
    void regenerateRequestIdWhenHeaderIsTooLong() throws Exception {
        // given
        String tooLongRequestId = "a".repeat(129);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(REQUEST_ID_HEADER, tooLongRequestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                requestIdInChain.set(MDC.get(REQUEST_ID_MDC_KEY));

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader(REQUEST_ID_HEADER)).isNotBlank();
        assertThat(response.getHeader(REQUEST_ID_HEADER)).isNotEqualTo(tooLongRequestId);
        assertThat(requestIdInChain.get()).isEqualTo(response.getHeader(REQUEST_ID_HEADER));
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("요청 처리 중 예외가 발생하면 예외를 다시 던지고 MDC를 정리한다.")
    void rethrowExceptionAndClearMdcWhenRequestFails() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            throw new ServletException("요청 처리 실패");
        };

        // when & then
        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("요청 처리 실패");

        assertThat(response.getHeader(REQUEST_ID_HEADER)).isNotBlank();
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("매칭된 핸들러 패턴이 있으면 정규화된 경로로 사용한다.")
    void resolveNormalizedPathFromBestMatchingPattern() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/users/{userId}");

        // when
        String normalizedPath = filter.resolveNormalizedPath(request);

        // then
        assertThat(normalizedPath).isEqualTo("/api/users/{userId}");
    }

    @Test
    @DisplayName("매칭된 핸들러 패턴이 없으면 요청 URI를 정규화된 경로로 사용한다.")
    void resolveNormalizedPathFromRequestUriWhenBestMatchingPatternIsMissing() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unknown");

        // when
        String normalizedPath = filter.resolveNormalizedPath(request);

        // then
        assertThat(normalizedPath).isEqualTo("/unknown");
    }
}
