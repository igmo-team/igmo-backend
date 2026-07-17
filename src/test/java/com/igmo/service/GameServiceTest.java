package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GamePhase;
import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PlayerView;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class GameServiceTest {

    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final TaskScheduler disconnectGraceScheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> scheduledRemoval = mock(ScheduledFuture.class);
    private final GameService gameService =
            new GameService(gameRegistry, roomCodeGenerator, messagingTemplate, disconnectGraceScheduler);

    @BeforeEach
    void 스케줄러가_예약_future를_반환하도록_설정한다() {
        ReflectionTestUtils.setField(gameService, "disconnectGrace", Duration.ofSeconds(3));
        given(disconnectGraceScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledRemoval);
    }

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
        verify(messagingTemplate).convertAndSend("/topic/rooms/ABCD", response.snapshot());
    }

    @Test
    @DisplayName("방 코드가 최대 시도 횟수 동안 계속 중복되면 RoomCodeGenerationFailedException을 던진다.")
    void createGame_최대_시도_횟수를_초과하면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameService.createGame("다음호스트"))
                .isInstanceOf(RoomCodeGenerationFailedException.class)
                .hasMessage("방 코드를 발급하지 못했습니다. 잠시 후 다시 시도해주세요.");
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

    @Test
    @DisplayName("참가자가 방을 나가면 남은 인원이 담긴 스냅샷을 브로드캐스트한다.")
    void leaveGame_참가자가_나가면_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse joined = gameService.joinGame("ABCD", "참가자");

        // when
        gameService.leaveGame("ABCD", joined.playerId(), joined.secret());

        // then
        LobbySnapshot snapshot = captureLastBroadcast(2);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.players()).hasSize(1);
            softly.assertThat(snapshot.players().get(0).id()).isEqualTo(created.playerId());
            softly.assertThat(snapshot.hostId()).isEqualTo(created.playerId());
        });
    }

    @Test
    @DisplayName("방장이 나가면 남은 참가자가 새 방장으로 지정된 스냅샷을 브로드캐스트한다.")
    void leaveGame_방장이_나가면_새_방장이_담긴_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse joined = gameService.joinGame("ABCD", "참가자");

        // when
        gameService.leaveGame("ABCD", created.playerId(), created.secret());

        // then
        LobbySnapshot snapshot = captureLastBroadcast(2);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.players()).hasSize(1);
            softly.assertThat(snapshot.hostId()).isEqualTo(joined.playerId());
        });
    }

    @Test
    @DisplayName("마지막 참가자가 나가면 방을 삭제하고 브로드캐스트하지 않는다.")
    void leaveGame_마지막_참가자가_나가면_방을_삭제하고_브로드캐스트하지_않는다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");

        // when
        gameService.leaveGame("ABCD", created.playerId(), created.secret());

        // then
        SoftAssertions.assertSoftly(softly ->
                softly.assertThat(gameRegistry.find("ABCD")).isEmpty());
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("존재하지 않는 방에서 나가면 RoomNotFoundException을 던진다.")
    void leaveGame_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gameService.leaveGame("ZZZZ", "player-id", "secret"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어가 나가면 PlayerNotFoundException을 던진다.")
    void leaveGame_방에_없는_플레이어면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameService.leaveGame("ABCD", "unknown-player-id", "secret"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("secret이 일치하지 않으면 UnauthorizedPlayerException을 던지고 플레이어를 제거하지 않는다.")
    void leaveGame_secret이_일치하지_않으면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");
        JoinGameResponse joined = gameService.joinGame("ABCD", "참가자");

        // when & then
        assertThatThrownBy(() -> gameService.leaveGame("ABCD", joined.playerId(), "wrong-secret"))
                .isInstanceOf(UnauthorizedPlayerException.class)
                .hasMessage("본인만 퇴장할 수 있습니다.");
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(joined.playerId()), "대상 플레이어가 방에 남아 있어야 한다");
    }

    @Test
    @DisplayName("삭제 예약 중 명시적으로 퇴장하면 즉시 제거하고 예약을 취소한다.")
    void leaveGame_삭제_예약을_취소하고_즉시_퇴장시킨다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");
        JoinGameResponse joined = gameService.joinGame("ABCD", "참가자");
        gameService.handleDisconnect("ABCD", joined.playerId());
        Runnable removal = captureScheduledRemoval();

        // when
        gameService.leaveGame("ABCD", joined.playerId(), joined.secret());
        boolean removedImmediately = gameRegistry.find("ABCD")
                .map(room -> !room.hasPlayer(joined.playerId()))
                .orElse(false);
        removal.run();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removedImmediately).isTrue();
            softly.assertThat(gameRegistry.find("ABCD")).get()
                    .matches(room -> !room.hasPlayer(joined.playerId()), "참가자가 제거된 상태여야 한다");
        });
        verify(scheduledRemoval).cancel(false);
        verify(messagingTemplate, times(2))
                .convertAndSend(eq("/topic/rooms/ABCD"), any(LobbySnapshot.class));
    }

    @Test
    @DisplayName("연결이 끊겨도 유예 시간 동안은 참가자를 제거하지 않고 삭제를 예약한다.")
    void handleDisconnect_직후에는_참가자를_제거하지_않고_삭제를_예약한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");
        JoinGameResponse joined = gameService.joinGame("ABCD", "참가자");

        // when
        gameService.handleDisconnect("ABCD", joined.playerId());

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(joined.playerId()), "참가자가 방에 남아 있어야 한다");
        verify(disconnectGraceScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("유예 시간이 지나면 예약된 작업이 참가자를 제거하고 스냅샷을 브로드캐스트한다.")
    void handleDisconnect_유예가_지나면_참가자를_제거하고_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse joined = gameService.joinGame("ABCD", "참가자");
        gameService.handleDisconnect("ABCD", joined.playerId());

        // when
        captureScheduledRemoval().run();

        // then
        LobbySnapshot snapshot = captureLastBroadcast(2);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.players()).hasSize(1);
            softly.assertThat(snapshot.players().get(0).id()).isEqualTo(created.playerId());
        });
    }

    @Test
    @DisplayName("유예 시간이 지나 방이 비면 방을 삭제하고 브로드캐스트하지 않는다.")
    void handleDisconnect_유예가_지나_방이_비면_방을_삭제한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        gameService.handleDisconnect("ABCD", created.playerId());

        // when
        captureScheduledRemoval().run();

        // then
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/room/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("존재하지 않는 방의 연결 끊김은 예약 작업이 실행돼도 예외 없이 무시한다.")
    void handleDisconnect_없는_방이면_무시한다() {
        // when & then
        assertThatCode(() -> {
            gameService.handleDisconnect("ZZZZ", "player-id");
            captureScheduledRemoval().run();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("방에 없는 플레이어의 연결 끊김은 예약 작업이 실행돼도 무시하고 브로드캐스트하지 않는다.")
    void handleDisconnect_방에_없는_플레이어면_무시한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");
        gameService.handleDisconnect("ABCD", "unknown-player-id");

        // when
        captureScheduledRemoval().run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("준비 상태를 변경하면 갱신된 스냅샷을 브로드캐스트한다.")
    void changeReady_준비_상태를_변경하면_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");

        // when
        gameService.changeReady("ABCD", created.playerId(), true);

        // then
        LobbySnapshot snapshot = captureLastBroadcast(1);
        PlayerView host = snapshot.players().get(0);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(host.id()).isEqualTo(created.playerId());
            softly.assertThat(host.ready()).isTrue();
        });
    }

    @Test
    @DisplayName("존재하지 않는 방에서 준비 상태를 변경하면 RoomNotFoundException을 던진다.")
    void changeReady_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gameService.changeReady("ZZZZ", "player-id", true))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어의 준비 상태를 변경하면 PlayerNotFoundException을 던진다.")
    void changeReady_방에_없는_플레이어면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameService.changeReady("ABCD", "unknown-player-id", true))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("방장이 시작하면 PROMPTING 단계로 진행한 스냅샷을 브로드캐스트한다.")
    void startGame_방장이_시작하면_다음_단계_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);

        // when
        gameService.startGame("ABCD", created.playerId());

        // then
        LobbySnapshot snapshot = captureLastBroadcast(5);
        assertThat(snapshot.phase()).isEqualTo(GamePhase.PROMPTING);
    }

    @Test
    @DisplayName("존재하지 않는 방을 시작하면 RoomNotFoundException을 던진다.")
    void startGame_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gameService.startGame("ZZZZ", "player-id"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방장이 아닌 참가자가 시작하면 도메인의 NotHostException을 전파한다.")
    void startGame_방장이_아니면_예외를_전파한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        gameService.joinGame("ABCD", "참가자2");

        // when & then
        assertThatThrownBy(() -> gameService.startGame("ABCD", guest1.playerId()))
                .isInstanceOf(NotHostException.class)
                .hasMessage("방장만 게임을 시작할 수 있습니다.");
    }

    @Test
    @DisplayName("연결 끊김 후 삭제 예약을 취소하면 예약된 future를 취소한다.")
    void cancelPendingRemoval_예약된_future를_취소한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        gameService.handleDisconnect("ABCD", created.playerId());

        // when
        gameService.cancelPendingRemoval("ABCD", created.playerId());

        // then
        verify(scheduledRemoval).cancel(false);
    }

    @Test
    @DisplayName("삭제 예약을 취소하면 이미 시작된 예약 작업이 실행돼도 참가자를 제거하지 않는다.")
    void cancelPendingRemoval_취소된_예약_작업은_참가자를_제거하지_않는다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        gameService.handleDisconnect("ABCD", created.playerId());
        Runnable removal = captureScheduledRemoval();
        gameService.cancelPendingRemoval("ABCD", created.playerId());

        // when
        removal.run();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(created.playerId()), "참가자가 방에 남아 있어야 한다");
    }

    @Test
    @DisplayName("삭제 예약이 없어도 취소 요청을 예외 없이 무시한다.")
    void cancelPendingRemoval_예약이_없으면_무시한다() {
        // when & then
        assertThatCode(() -> gameService.cancelPendingRemoval("ABCD", "player-id"))
                .doesNotThrowAnyException();
    }

    private Runnable captureScheduledRemoval() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(disconnectGraceScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    private LobbySnapshot captureLastBroadcast(int expectedBroadcastCount) {
        ArgumentCaptor<LobbySnapshot> captor = ArgumentCaptor.forClass(LobbySnapshot.class);
        verify(messagingTemplate, times(expectedBroadcastCount))
                .convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        return captor.getValue();
    }
}
