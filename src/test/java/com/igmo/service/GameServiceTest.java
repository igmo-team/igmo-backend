package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.igmo.exception.DuplicateNicknameException;
import com.igmo.exception.RoomNotFoundException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameServiceTest {

    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final GameService gameService = new GameService(gameRegistry, roomCodeGenerator);

    @Test
    @DisplayName("게임을 생성하면 방 코드와 호스트 playerId를 반환하고 레지스트리에 저장한다.")
    void createGame_방을_생성하고_저장한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");

        // when
        CreateGameResponse response = gameService.createGame("호스트");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.roomCode()).isEqualTo("ABCD");
            softly.assertThat(response.playerId()).isNotBlank();
            softly.assertThat(gameRegistry.find("ABCD")).isPresent();
        });
    }

    @Test
    @DisplayName("방 코드가 중복되면 새 코드를 다시 발급해 저장한다.")
    void createGame_코드가_중복되면_재발급한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD", "ABCD", "WXYZ");

        // when
        CreateGameResponse first = gameService.createGame("호스트1");
        CreateGameResponse second = gameService.createGame("호스트2");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(first.roomCode()).isEqualTo("ABCD");
            softly.assertThat(second.roomCode()).isEqualTo("WXYZ");
            softly.assertThat(gameRegistry.find("ABCD")).isPresent();
            softly.assertThat(gameRegistry.find("WXYZ")).isPresent();
        });
    }

    @Test
    @DisplayName("코드로 참여하면 playerId와 두 명이 담긴 로비 스냅샷을 반환한다.")
    void joinGame_참여하면_스냅샷을_반환한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");

        // when
        JoinGameResponse response = gameService.joinGame("ABCD", "참가자");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.playerId()).isNotBlank();
            softly.assertThat(response.snapshot().roomCode()).isEqualTo("ABCD");
            softly.assertThat(response.snapshot().players()).hasSize(2);
            softly.assertThat(response.snapshot().players())
                    .extracting(player -> player.nickname())
                    .containsExactly("호스트", "참가자");
        });
    }

    @Test
    @DisplayName("존재하지 않는 코드로 참여하면 RoomNotFoundException을 던진다.")
    void joinGame_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gameService.joinGame("ZZZZ", "참가자"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임으로 참여하면 DuplicateNicknameException을 던진다.")
    void joinGame_닉네임이_중복되면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameService.joinGame("ABCD", "호스트"))
                .isInstanceOf(DuplicateNicknameException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }
}
