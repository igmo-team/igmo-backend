package com.igmo.web;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.JsonFieldType.ARRAY;
import static org.springframework.restdocs.payload.JsonFieldType.BOOLEAN;
import static org.springframework.restdocs.payload.JsonFieldType.NUMBER;
import static org.springframework.restdocs.payload.JsonFieldType.OBJECT;
import static org.springframework.restdocs.payload.JsonFieldType.STRING;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.igmo.domain.GamePhase;
import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.RoomFullException;
import com.igmo.service.GameLobbyService;
import com.igmo.service.GameService;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PlayerView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GameController.class)
@AutoConfigureRestDocs
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameLobbyService gameLobbyService;

    @MockitoBean
    private GameService gameService;

    @Test
    @DisplayName("게임 생성에 성공하면 201과 방 코드, playerId, 초기 로비 스냅샷을 반환한다.")
    void createGame_성공하면_201을_반환한다() throws Exception {
        // given
        LobbySnapshot snapshot = new LobbySnapshot("ABCD", GamePhase.LOBBY, "host-id",
                List.of(new PlayerView("host-id", "host", 0, false)));
        given(gameLobbyService.createGame("host"))
                .willReturn(new CreateGameResponse("ABCD", "host-id", "host-secret", snapshot));

        // when & then
        mockMvc.perform(post("/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"host\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomCode").value("ABCD"))
                .andExpect(jsonPath("$.playerId").value("host-id"))
                .andExpect(jsonPath("$.secret").value("host-secret"))
                .andExpect(jsonPath("$.snapshot.roomCode").value("ABCD"))
                .andExpect(jsonPath("$.snapshot.hostId").value("host-id"))
                .andExpect(jsonPath("$.snapshot.players.length()").value(1))
                .andDo(document("create-game",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 생성")
                                .description("호스트 닉네임으로 새 게임 방을 생성합니다.")
                                .requestFields(nicknameField("호스트 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다."))
                                .responseFields(createGameResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("닉네임이 비어 있으면 400을 반환한다.")
    void createGame_닉네임이_비면_400을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임을 입력해주세요."))
                .andDo(document("create-game-invalid-nickname",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 생성")
                                .description("호스트 닉네임으로 새 게임 방을 생성합니다.")
                                .requestFields(nicknameField("호스트 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다."))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("코드로 참여하면 200과 스냅샷을 반환한다.")
    void joinGame_성공하면_200을_반환한다() throws Exception {
        // given
        LobbySnapshot snapshot = new LobbySnapshot("ABCD", GamePhase.LOBBY, "host-id",
                List.of(new PlayerView("host-id", "host", 0, false),
                        new PlayerView("guest-id", "guest", 0, false)));
        given(gameLobbyService.joinGame("ABCD", "guest"))
                .willReturn(new JoinGameResponse("guest-id", "guest-secret", snapshot));

        // when & then
        mockMvc.perform(post("/games/{code}/players", "ABCD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"guest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("guest-id"))
                .andExpect(jsonPath("$.secret").value("guest-secret"))
                .andExpect(jsonPath("$.snapshot.roomCode").value("ABCD"))
                .andExpect(jsonPath("$.snapshot.players.length()").value(2))
                .andDo(document("join-game",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 참여")
                                .description("기존 게임 방에 새 플레이어로 참여합니다.")
                                .pathParameters(parameterWithName("code").description("참여할 방 코드"))
                                .requestFields(nicknameField("참여자 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다."))
                                .responseFields(joinGameResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("존재하지 않는 방에 참여하면 404를 반환한다.")
    void joinGame_없는_방이면_404를_반환한다() throws Exception {
        // given
        given(gameLobbyService.joinGame("ZZZZ", "guest")).willThrow(new RoomNotFoundException());

        // when & then
        mockMvc.perform(post("/games/{code}/players", "ZZZZ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"guest\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("방을 찾을 수 없습니다."))
                .andDo(document("join-game-room-not-found",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 참여")
                                .description("기존 게임 방에 새 플레이어로 참여합니다.")
                                .pathParameters(parameterWithName("code").description("참여할 방 코드"))
                                .requestFields(nicknameField("참여자 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다."))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("정원이 가득 찬 방에 참여하면 403을 반환한다.")
    void joinGame_정원이_가득_차면_403을_반환한다() throws Exception {
        // given
        given(gameLobbyService.joinGame("ABCD", "guest")).willThrow(new RoomFullException());

        // when & then
        mockMvc.perform(post("/games/{code}/players", "ABCD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"guest\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("방 정원이 가득 찼습니다."))
                .andDo(document("join-game-room-full",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 참여")
                                .description("기존 게임 방에 새 플레이어로 참여합니다.")
                                .pathParameters(parameterWithName("code").description("참여할 방 코드"))
                                .requestFields(nicknameField("참여자 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다."))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("닉네임이 중복되면 409를 반환한다.")
    void joinGame_닉네임이_중복되면_409를_반환한다() throws Exception {
        // given
        given(gameLobbyService.joinGame("ABCD", "host")).willThrow(new DuplicateNicknameException());

        // when & then
        mockMvc.perform(post("/games/{code}/players", "ABCD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"host\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 닉네임입니다."))
                .andDo(document("join-game-duplicate-nickname",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 참여")
                                .description("기존 게임 방에 새 플레이어로 참여합니다.")
                                .pathParameters(parameterWithName("code").description("참여할 방 코드"))
                                .requestFields(nicknameField("참여자 닉네임. 앞뒤 공백은 제거되며 2~10자만 허용됩니다."))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("방을 나가면 204를 반환한다.")
    void leaveGame_성공하면_204를_반환한다() throws Exception {
        // when & then
        mockMvc.perform(delete("/games/{code}/players/{playerId}", "ABCD", "guest-id")
                        .header("X-Player-Secret", "guest-secret"))
                .andExpect(status().isNoContent())
                .andDo(document("leave-game",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 퇴장")
                                .description("플레이어 secret을 검증한 뒤 방에서 퇴장합니다.")
                                .pathParameters(
                                        parameterWithName("code").description("방 코드"),
                                        parameterWithName("playerId").description("퇴장할 플레이어 ID")
                                )
                                .requestHeaders(headerWithName("X-Player-Secret").description("플레이어 인증 secret"))
                                .build())));
    }

    @Test
    @DisplayName("secret 헤더가 없으면 400을 반환한다.")
    void leaveGame_secret_헤더가_없으면_400을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(delete("/games/{code}/players/{playerId}", "ABCD", "guest-id"))
                .andExpect(status().isBadRequest())
                .andDo(document("leave-game-missing-secret",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 퇴장")
                                .description("플레이어 secret을 검증한 뒤 방에서 퇴장합니다.")
                                .pathParameters(
                                        parameterWithName("code").description("방 코드"),
                                        parameterWithName("playerId").description("퇴장할 플레이어 ID")
                                )
                                .build())));
    }

    @Test
    @DisplayName("secret이 일치하지 않으면 403을 반환한다.")
    void leaveGame_secret이_일치하지_않으면_403을_반환한다() throws Exception {
        // given
        willThrow(new UnauthorizedPlayerException())
                .given(gameService).leaveGame("ABCD", "guest-id", "wrong-secret");

        // when & then
        mockMvc.perform(delete("/games/{code}/players/{playerId}", "ABCD", "guest-id")
                        .header("X-Player-Secret", "wrong-secret"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인만 퇴장할 수 있습니다."))
                .andDo(document("leave-game-unauthorized",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 퇴장")
                                .description("플레이어 secret을 검증한 뒤 방에서 퇴장합니다.")
                                .pathParameters(
                                        parameterWithName("code").description("방 코드"),
                                        parameterWithName("playerId").description("퇴장할 플레이어 ID")
                                )
                                .requestHeaders(headerWithName("X-Player-Secret").description("플레이어 인증 secret"))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("존재하지 않는 방에서 나가면 404를 반환한다.")
    void leaveGame_없는_방이면_404를_반환한다() throws Exception {
        // given
        willThrow(new RoomNotFoundException()).given(gameService).leaveGame("ZZZZ", "guest-id", "guest-secret");

        // when & then
        mockMvc.perform(delete("/games/{code}/players/{playerId}", "ZZZZ", "guest-id")
                        .header("X-Player-Secret", "guest-secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("방을 찾을 수 없습니다."))
                .andDo(document("leave-game-room-not-found",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 퇴장")
                                .description("플레이어 secret을 검증한 뒤 방에서 퇴장합니다.")
                                .pathParameters(
                                        parameterWithName("code").description("방 코드"),
                                        parameterWithName("playerId").description("퇴장할 플레이어 ID")
                                )
                                .requestHeaders(headerWithName("X-Player-Secret").description("플레이어 인증 secret"))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    @Test
    @DisplayName("방에 없는 플레이어가 나가면 404를 반환한다.")
    void leaveGame_방에_없는_플레이어면_404를_반환한다() throws Exception {
        // given
        willThrow(new PlayerNotFoundException()).given(gameService).leaveGame("ABCD", "unknown-id", "guest-secret");

        // when & then
        mockMvc.perform(delete("/games/{code}/players/{playerId}", "ABCD", "unknown-id")
                        .header("X-Player-Secret", "guest-secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("방에 없는 플레이어입니다."))
                .andDo(document("leave-game-player-not-found",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Games")
                                .summary("게임 방 퇴장")
                                .description("플레이어 secret을 검증한 뒤 방에서 퇴장합니다.")
                                .pathParameters(
                                        parameterWithName("code").description("방 코드"),
                                        parameterWithName("playerId").description("퇴장할 플레이어 ID")
                                )
                                .requestHeaders(headerWithName("X-Player-Secret").description("플레이어 인증 secret"))
                                .responseFields(errorResponseFields())
                                .build())));
    }

    private static FieldDescriptor nicknameField(String description) {
        return fieldWithPath("nickname").type(STRING).description(description);
    }

    private static FieldDescriptor[] errorResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("message").type(STRING).description("에러 메시지")
        };
    }

    private static FieldDescriptor[] createGameResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("roomCode").type(STRING).description("생성된 방 코드"),
                fieldWithPath("playerId").type(STRING).description("호스트 플레이어 ID"),
                fieldWithPath("secret").type(STRING).description("호스트 플레이어 인증 secret"),
                fieldWithPath("snapshot").type(OBJECT).description("생성 직후 로비 상태"),
                fieldWithPath("snapshot.roomCode").type(STRING).description("방 코드"),
                fieldWithPath("snapshot.phase").type(STRING).description("게임 진행 단계"),
                fieldWithPath("snapshot.hostId").type(STRING).description("현재 호스트 플레이어 ID"),
                fieldWithPath("snapshot.players").type(ARRAY).description("방에 참여 중인 플레이어 목록"),
                fieldWithPath("snapshot.players[].id").type(STRING).description("플레이어 ID"),
                fieldWithPath("snapshot.players[].nickname").type(STRING).description("닉네임"),
                fieldWithPath("snapshot.players[].score").type(NUMBER).description("현재 점수"),
                fieldWithPath("snapshot.players[].ready").type(BOOLEAN).description("준비 완료 여부")
        };
    }

    private static FieldDescriptor[] joinGameResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("playerId").type(STRING).description("참여한 플레이어 ID"),
                fieldWithPath("secret").type(STRING).description("참여한 플레이어 인증 secret"),
                fieldWithPath("snapshot").type(OBJECT).description("참여 직후 로비 상태"),
                fieldWithPath("snapshot.roomCode").type(STRING).description("방 코드"),
                fieldWithPath("snapshot.phase").type(STRING).description("게임 진행 단계"),
                fieldWithPath("snapshot.hostId").type(STRING).description("현재 호스트 플레이어 ID"),
                fieldWithPath("snapshot.players").type(ARRAY).description("방에 참여 중인 플레이어 목록"),
                fieldWithPath("snapshot.players[].id").type(STRING).description("플레이어 ID"),
                fieldWithPath("snapshot.players[].nickname").type(STRING).description("닉네임"),
                fieldWithPath("snapshot.players[].score").type(NUMBER).description("현재 점수"),
                fieldWithPath("snapshot.players[].ready").type(BOOLEAN).description("준비 완료 여부")
        };
    }
}
