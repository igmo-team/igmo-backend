package com.igmo.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PREFIX = "/admin/";
    private static final String BASIC_PREFIX = "Basic ";
    private static final String CONFIGURATION_ERROR_MESSAGE = "관리자 인증 설정이 완료되지 않았습니다.";
    private static final String CREDENTIALS_REQUIRED_MESSAGE = "관리자 로그인이 필요합니다.";
    private static final String INVALID_CREDENTIALS_MESSAGE = "관리자 아이디 또는 비밀번호가 올바르지 않습니다.";

    private final byte[] expectedCredentials;
    private final boolean configured;

    public AdminAuthenticationFilter(
            @Value("${igmo.admin.image-generation.username:}") String username,
            @Value("${igmo.admin.image-generation.password:}") String password
    ) {
        expectedCredentials = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        configured = !username.isBlank() && !password.isBlank();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ADMIN_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String failureMessage = authenticationFailureMessage(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (failureMessage != null) {
            log.atWarn()
                    .addKeyValue("event", "admin_authentication_failed")
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", HttpServletResponse.SC_UNAUTHORIZED)
                    .addKeyValue("reason", failureMessage)
                    .log("admin authentication failed");
            writeAuthenticationFailure(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String authenticationFailureMessage(String authorization) {
        if (!configured) {
            return CONFIGURATION_ERROR_MESSAGE;
        }
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return CREDENTIALS_REQUIRED_MESSAGE;
        }
        try {
            byte[] providedCredentials = Base64.getDecoder().decode(authorization.substring(BASIC_PREFIX.length()));
            return MessageDigest.isEqual(expectedCredentials, providedCredentials) ? null : INVALID_CREDENTIALS_MESSAGE;
        } catch (IllegalArgumentException exception) {
            return INVALID_CREDENTIALS_MESSAGE;
        }
    }

    private void writeAuthenticationFailure(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"igmo-admin\", charset=\"UTF-8\"");
    }
}
