package com.igmo.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startedAt = System.nanoTime();

        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            logError(request, response, exception);
            throw exception;
        } finally {
            logAccess(request, response, startedAt);
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);

        if (isValidRequestId(requestId)) {
            return requestId;
        }

        return UUID.randomUUID().toString();
    }

    private boolean isValidRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            return false;
        }

        return requestId.chars().allMatch(this::isVisibleAscii);
    }

    private boolean isVisibleAscii(int character) {
        return character >= 33 && character <= 126;
    }

    private void logAccess(
            HttpServletRequest request,
            HttpServletResponse response,
            long startedAt
    ) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        log.atInfo()
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .addKeyValue("normalizedPath", resolveNormalizedPath(request))
                .addKeyValue("status", response.getStatus())
                .addKeyValue("durationMs", durationMs)
                .log("HTTP request completed");
    }

    private void logError(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception exception
    ) {
        log.atError()
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .addKeyValue("normalizedPath", resolveNormalizedPath(request))
                .addKeyValue("status", response.getStatus())
                .addKeyValue("exception", exception.getClass().getSimpleName())
                .log("Unhandled exception", exception);
    }

    String resolveNormalizedPath(HttpServletRequest request) {
        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        if (bestMatchingPattern instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }

        return request.getRequestURI();
    }
}
