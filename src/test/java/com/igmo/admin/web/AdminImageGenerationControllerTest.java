package com.igmo.admin.web;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.JsonFieldType.ARRAY;
import static org.springframework.restdocs.payload.JsonFieldType.NUMBER;
import static org.springframework.restdocs.payload.JsonFieldType.STRING;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.igmo.admin.application.AdminImageGenerationService;
import com.igmo.admin.web.dto.AdminImageGenerationOptionsResponse;
import com.igmo.admin.web.dto.AdminImageGenerationRequest;
import com.igmo.admin.web.dto.AdminImageGenerationResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminImageGenerationController.class)
@AutoConfigureRestDocs
@TestPropertySource(properties = {
        "igmo.admin.image-generation.username=admin",
        "igmo.admin.image-generation.password=secret"
})
class AdminImageGenerationControllerTest {

    private static final String BASIC_AUTH = "Basic YWRtaW46c2VjcmV0";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminImageGenerationService adminImageGenerationService;

    @Test
    @DisplayName("관리자 옵션을 조회하면 허용 모델과 이미지 크기를 반환한다.")
    void getOptions_성공하면_허용옵션을반환한다() throws Exception {
        // given
        given(adminImageGenerationService.getOptions())
                .willReturn(new AdminImageGenerationOptionsResponse(List.of("gemini-image"), List.of("1K", "2K")));

        // when & then
        mockMvc.perform(get("/admin/image-generation/options").header(HttpHeaders.AUTHORIZATION, BASIC_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0]").value("gemini-image"))
                .andExpect(jsonPath("$.imageSizes[1]").value("2K"))
                .andDo(document("admin-image-generation-options",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Admin")
                                .summary("이미지 생성 선택 옵션 조회")
                                .description("관리자 화면에서 선택할 모델과 이미지 크기 목록을 반환합니다.")
                                .requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION).description("관리자 Basic 인증"))
                                .responseFields(
                                        fieldWithPath("models").type(ARRAY).description("허용 모델 목록"),
                                        fieldWithPath("imageSizes").type(ARRAY).description("허용 이미지 크기 목록")
                                )
                                .build())));
    }

    @Test
    @DisplayName("관리자 인증 없이 요청하면 401을 반환한다.")
    void getOptions_인증없으면_401을반환한다() throws Exception {
        // when & then
        mockMvc.perform(get("/admin/image-generation/options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("허용된 요청으로 이미지를 생성하면 data URL을 반환한다.")
    void generate_성공하면_dataUrl을반환한다() throws Exception {
        // given
        given(adminImageGenerationService.generate(new AdminImageGenerationRequest("토끼", "gemini-image", "2K")))
                .willReturn(new AdminImageGenerationResponse(
                        "data:image/jpeg;base64,aW1hZ2U=", "s3://admin-bucket/generated-admin-images/image.jpg", "gemini-image", "2K", 1200));

        // when & then
        mockMvc.perform(post("/admin/image-generation")
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"토끼\",\"model\":\"gemini-image\",\"imageSize\":\"2K\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageDataUrl").value("data:image/jpeg;base64,aW1hZ2U="))
                .andExpect(jsonPath("$.durationMs").value(1200))
                .andDo(document("admin-image-generation",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Admin")
                                .summary("관리자 이미지 생성 테스트")
                                .description("선택한 모델과 이미지 크기로 이미지를 생성하고 브라우저 미리보기용 data URL을 반환합니다.")
                                .requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION).description("관리자 Basic 인증"))
                                .requestFields(
                                        fieldWithPath("prompt").type(STRING).description("이미지 생성 프롬프트"),
                                        fieldWithPath("model").type(STRING).description("허용 모델"),
                                        fieldWithPath("imageSize").type(STRING).description("허용 이미지 크기")
                                )
                                .responseFields(responseFields())
                                .build())));
    }

    private FieldDescriptor[] responseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("imageDataUrl").type(STRING).description("생성 이미지 data URL"),
                fieldWithPath("storageUri").type(STRING).description("관리자 전용 S3 저장 위치"),
                fieldWithPath("model").type(STRING).description("사용 모델"),
                fieldWithPath("imageSize").type(STRING).description("사용 이미지 크기"),
                fieldWithPath("durationMs").type(NUMBER).description("생성 시간(ms)")
        };
    }
}
