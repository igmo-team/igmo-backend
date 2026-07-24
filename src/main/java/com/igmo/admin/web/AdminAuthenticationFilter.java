package com.igmo.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PREFIX = "/admin/";
    private static final String BASIC_PREFIX = "Basic ";

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
        if (!hasValidCredentials(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"igmo-admin\", charset=\"UTF-8\"");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasValidCredentials(String authorization) {
        if (!configured || authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return false;
        }
        try {
            byte[] providedCredentials = Base64.getDecoder().decode(authorization.substring(BASIC_PREFIX.length()));
            return MessageDigest.isEqual(expectedCredentials, providedCredentials);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
