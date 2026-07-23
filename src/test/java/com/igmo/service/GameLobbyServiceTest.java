package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.monitoring.GameMetrics;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PlayerView;
import com.igmo.web.dto.RoomMessage;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class GameLobbyServiceTest {

    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final GameLobbyService gameLobbyService = new GameLobbyService(
            new GameRoomRepository(gameRegistry),
            roomCodeGenerator,
            new GameEventPublisher(messagingTemplate, gameMetrics));

    @Test
    @DisplayName("게임을 생성하면 방 코드와 호스트 playerId를 반환하고 레지스트리에 저장한다.")
    void createGame_방을_생성하고_저장한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");

        // when
        CreateGameResponse response = gameLobbyService.createGame("호스트");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.roomCode()).isEqualTo("ABCD");
            softly.assertThat(response.playerId()).isNotBlank();
            softly.assertThat(response.snapshot().roomCode()).isEqualTo("ABCD");
            softly.assertThat(response.snapshot().hostId()).isEqualTo(response.playerId());
            softly.assertThat(response.snapshot().players()).hasSize(1);
            softly.assertThat(response.snapshot().players().get(0).nickname()).isEqualTo("호스트");
            softly.assertThat(gameRegistry.find("ABCD")).isPresent();
        });
    }

    @Test
    @DisplayName("방 코드가 중복되면 새 코드를 다시 발급해 저장한다.")
    void createGame_코드가_중복되면_재발급한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD", "ABCD", "WXYZ");

        // when
        CreateGameResponse first = gameLobbyService.createGame("호스트1");
        CreateGameResponse second = gameLobbyService.createGame("호스트2");

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
        gameLobbyService.createGame("호스트");

        // when
        JoinGameResponse response = gameLobbyService.joinGame("ABCD", "참가자");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.playerId()).isNotBlank();
            softly.assertThat(response.snapshot().roomCode()).isEqualTo("ABCD");
            softly.assertThat(response.snapshot().players()).hasSize(2);
            softly.assertThat(response.snapshot().players())
                    .extracting(PlayerView::nickname)
                    .containsExactly("호스트", "참가자");
        });
        verify(messagingTemplate).convertAndSend("/topic/rooms/ABCD", RoomMessage.lobbySnapshot(response.snapshot()));
    }

    @Test
    @DisplayName("방 코드가 최대 시도 횟수 동안 계속 중복되면 RoomCodeGenerationFailedException을 던진다.")
    void createGame_최대_시도_횟수를_초과하면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameLobbyService.createGame("다음호스트"))
                .isInstanceOf(RoomCodeGenerationFailedException.class)
                .hasMessage("방 코드를 발급하지 못했습니다. 잠시 후 다시 시도해주세요.");
    }

    @Test
    @DisplayName("존재하지 않는 코드로 참여하면 RoomNotFoundException을 던진다.")
    void joinGame_없는_방이면_예외를_던진다() {
        assertThatThrownBy(() -> gameLobbyService.joinGame("ZZZZ", "참가자"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임으로 참여하면 DuplicateNicknameException을 던진다.")
    void joinGame_닉네임이_중복되면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameLobbyService.joinGame("ABCD", "호스트"))
                .isInstanceOf(DuplicateNicknameException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    @DisplayName("준비 상태를 변경하면 갱신된 스냅샷을 브로드캐스트한다.")
    void changeReady_준비_상태를_변경하면_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");

        // when
        gameLobbyService.changeReady("ABCD", created.playerId(), true);

        // then
        LobbySnapshot snapshot = LobbySnapshot.from(gameRegistry.find("ABCD").orElseThrow());
        PlayerView host = snapshot.players().getFirst();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(host.id()).isEqualTo(created.playerId());
            softly.assertThat(host.ready()).isTrue();
        });
        verify(messagingTemplate).convertAndSend("/topic/rooms/ABCD", RoomMessage.lobbySnapshot(snapshot));
    }

    @Test
    @DisplayName("존재하지 않는 방에서 준비 상태를 변경하면 RoomNotFoundException을 던진다.")
    void changeReady_없는_방이면_예외를_던진다() {
        assertThatThrownBy(() -> gameLobbyService.changeReady("ZZZZ", "player-id", true))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어의 준비 상태를 변경하면 PlayerNotFoundException을 던진다.")
    void changeReady_방에_없는_플레이어면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameLobbyService.changeReady("ABCD", "unknown-player-id", true))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }
}
