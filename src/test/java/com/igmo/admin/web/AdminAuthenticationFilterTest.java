package com.igmo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthenticationFilterTest {

    @Test
    @DisplayName("관리자 인증 정보가 없으면 실패 사유를 응답에 노출하지 않는다.")
    void doFilter_인증정보없으면_실패사유를응답에노출하지않는다() throws Exception {
        AdminAuthenticationFilter filter = new AdminAuthenticationFilter("admin", "secret");
        MockHttpServletResponse response = filter("/admin/", null, filter);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("Basic realm=\"igmo-admin\"");
        assertThat(response.getHeader("X-Admin-Authentication-Error")).isNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("관리자 인증 정보가 틀리면 실패 사유를 응답에 노출하지 않는다.")
    void doFilter_인증정보틀리면_실패사유를응답에노출하지않는다() throws Exception {
        AdminAuthenticationFilter filter = new AdminAuthenticationFilter("admin", "secret");
        MockHttpServletResponse response = filter("/admin/", "Basic YWRtaW46d3Jvbmc=", filter);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Admin-Authentication-Error")).isNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("관리자 인증 설정이 없으면 실패 사유를 응답에 노출하지 않는다.")
    void doFilter_인증설정없으면_실패사유를응답에노출하지않는다() throws Exception {
        AdminAuthenticationFilter filter = new AdminAuthenticationFilter("", "");
        MockHttpServletResponse response = filter("/admin/", "Basic YWRtaW46c2VjcmV0", filter);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Admin-Authentication-Error")).isNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("올바른 관리자 인증 정보면 다음 필터로 전달한다.")
    void doFilter_인증성공하면_다음필터로전달한다() throws Exception {
        AdminAuthenticationFilter filter = new AdminAuthenticationFilter("admin", "secret");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46c2VjcmV0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isEqualTo(request);
    }

    private MockHttpServletResponse filter(String requestUri, String authorization, AdminAuthenticationFilter filter)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
