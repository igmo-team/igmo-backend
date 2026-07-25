package com.igmo.admin.web;

import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminPageController.class)
@TestPropertySource(properties = {
        "igmo.admin.image-generation.username=admin",
        "igmo.admin.image-generation.password=secret"
})
class AdminPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("관리자 루트 경로로 접속하면 홈 대시보드로 전달한다.")
    void adminHome_대시보드로전달한다() throws Exception {
        // when & then
        mockMvc.perform(get("/admin/").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46c2VjcmV0"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"));
    }

    @Test
    @DisplayName("관리자 홈은 절대 경로의 스타일 자산을 제공한다.")
    void adminHome_스타일자산을제공한다() throws Exception {
        // when & then
        mockMvc.perform(get("/admin/admin.css").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46c2VjcmV0"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    @DisplayName("관리자 홈은 이그모 로고 자산을 제공한다.")
    void adminHome_이그모로고를제공한다() throws Exception {
        // when & then
        mockMvc.perform(get("/admin/assets/igmo-logo.png").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46c2VjcmV0"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"));
    }

    @Test
    @DisplayName("관리자 이미지 생성 경로로 접속하면 정적 페이지로 전달한다.")
    void imageGenerationPage_정적페이지로전달한다() throws Exception {
        // when & then
        mockMvc.perform(get("/admin/image-generation/").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46c2VjcmV0"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/image-generation/index.html"));
    }
}
