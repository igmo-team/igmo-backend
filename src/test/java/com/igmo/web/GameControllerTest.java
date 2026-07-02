package com.igmo.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.GamePhase;
import com.igmo.domain.exception.RoomFullException;
import com.igmo.service.GameService;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PlayerView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GameController.class)
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService gameService;

    @Test
    @DisplayName("게임 생성에 성공하면 201과 방 코드, playerId, 초기 로비 스냅샷을 반환한다.")
    void createGame_성공하면_201을_반환한다() throws Exception {
        // given
        LobbySnapshot snapshot = new LobbySnapshot("ABCD", GamePhase.LOBBY, "host-id",
                List.of(new PlayerView("host-id", "host", 0)));
        given(gameService.createGame("host"))
                .willReturn(new CreateGameResponse("ABCD", "host-id", snapshot));

        // when & then
        mockMvc.perform(post("/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"host\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomCode").value("ABCD"))
                .andExpect(jsonPath("$.playerId").value("host-id"))
                .andExpect(jsonPath("$.snapshot.roomCode").value("ABCD"))
                .andExpect(jsonPath("$.snapshot.hostId").value("host-id"))
                .andExpect(jsonPath("$.snapshot.players.length()").value(1));
    }

    @Test
    @DisplayName("닉네임이 비어 있으면 400을 반환한다.")
    void createGame_닉네임이_비면_400을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("코드로 참여하면 200과 스냅샷을 반환한다.")
    void joinGame_성공하면_200을_반환한다() throws Exception {
        // given
        LobbySnapshot snapshot = new LobbySnapshot("ABCD", GamePhase.LOBBY, "host-id",
                List.of(new PlayerView("host-id", "host", 0),
                        new PlayerView("guest-id", "guest", 0)));
        given(gameService.joinGame("ABCD", "guest"))
                .willReturn(new JoinGameResponse("guest-id", snapshot));

        // when & then
        mockMvc.perform(post("/games/ABCD/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"guest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("guest-id"))
                .andExpect(jsonPath("$.snapshot.roomCode").value("ABCD"))
                .andExpect(jsonPath("$.snapshot.players.length()").value(2));
    }

    @Test
    @DisplayName("존재하지 않는 방에 참여하면 404를 반환한다.")
    void joinGame_없는_방이면_404를_반환한다() throws Exception {
        // given
        given(gameService.joinGame("ZZZZ", "guest")).willThrow(new RoomNotFoundException());

        // when & then
        mockMvc.perform(post("/games/ZZZZ/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"guest\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("정원이 가득 찬 방에 참여하면 403을 반환한다.")
    void joinGame_정원이_가득_차면_403을_반환한다() throws Exception {
        // given
        given(gameService.joinGame("ABCD", "guest")).willThrow(new RoomFullException());

        // when & then
        mockMvc.perform(post("/games/ABCD/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"guest\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("닉네임이 중복되면 409를 반환한다.")
    void joinGame_닉네임이_중복되면_409를_반환한다() throws Exception {
        // given
        given(gameService.joinGame("ABCD", "host")).willThrow(new DuplicateNicknameException());

        // when & then
        mockMvc.perform(post("/games/ABCD/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"host\"}"))
                .andExpect(status().isConflict());
    }
}
