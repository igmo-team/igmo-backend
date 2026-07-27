package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameStartPolicy;
import com.igmo.monitoring.GameMetrics;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
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

class PlayerPresenceServiceTest {

    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final TaskScheduler disconnectGraceScheduler = mock(TaskScheduler.class);
    private final TaskScheduler gamePhaseDeadlineScheduler = mock(TaskScheduler.class);
    private final TaskScheduler imageGenerationCompletionScheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> scheduledRemoval = mock(ScheduledFuture.class);
    private final ScheduledFuture<?> scheduledPlayingTransition = mock(ScheduledFuture.class);
    private final GamePhaseScheduler gamePhaseScheduler = spy(new GamePhaseScheduler(
            gamePhaseDeadlineScheduler,
            imageGenerationCompletionScheduler));
    private final GameLobbyService gameLobbyService = new GameLobbyService(
            new GameRoomRepository(gameRegistry),
            roomCodeGenerator,
            new GameEventPublisher(messagingTemplate, gameMetrics),
            GameStartPolicy.standard());
    private final PlayerPresenceService playerPresenceService = new PlayerPresenceService(
            new GameRoomRepository(gameRegistry),
            gamePhaseScheduler,
            new GameEventPublisher(messagingTemplate, gameMetrics),
            disconnectGraceScheduler);

    @BeforeEach
    void 연결_해제_삭제_예약을_설정한다() {
        ReflectionTestUtils.setField(playerPresenceService, "disconnectGrace", Duration.ofSeconds(3));
        given(disconnectGraceScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledRemoval);
        given(imageGenerationCompletionScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPlayingTransition);
    }

    @Test
    @DisplayName("참가자가 방을 나가면 남은 인원이 담긴 스냅샷을 브로드캐스트한다.")
    void leaveGame_참가자가_나가면_스냅샷을_브로드캐스트한다() {
        // given
        GameSession session = createLobby();

        // when
        playerPresenceService.leaveGame("ABCD", session.guest1().playerId(), session.guest1().secret());

        // then
        LobbySnapshot snapshot = captureLastLobbyBroadcast(3);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.players()).hasSize(2);
            softly.assertThat(snapshot.players()).extracting(player -> player.id())
                    .containsExactly(session.host().playerId(), session.guest2().playerId());
            softly.assertThat(snapshot.hostId()).isEqualTo(session.host().playerId());
        });
    }

    @Test
    @DisplayName("방장이 나가면 남은 참가자 중 한 명을 새 방장으로 지정해 브로드캐스트한다.")
    void leaveGame_방장이_나가면_새_방장이_담긴_스냅샷을_브로드캐스트한다() {
        // given
        GameSession session = createLobby();

        // when
        playerPresenceService.leaveGame("ABCD", session.host().playerId(), session.host().secret());

        // then
        LobbySnapshot snapshot = captureLastLobbyBroadcast(3);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.players()).hasSize(2);
            softly.assertThat(snapshot.hostId())
                    .isIn(session.guest1().playerId(), session.guest2().playerId());
        });
    }

    @Test
    @DisplayName("마지막 참가자가 나가면 방과 게임 단계 예약을 삭제한다.")
    void leaveGame_마지막_참가자가_나가면_방을_삭제하고_예약을_취소한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");

        // when
        playerPresenceService.leaveGame("ABCD", created.playerId(), created.secret());

        // then
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(gamePhaseScheduler).cancelAll("ABCD");
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("마지막 참가자가 나가면 예약된 PLAYING 전환을 취소한다.")
    void leaveGame_방이_비면_PLAYING_전환_예약을_취소한다() {
        // given
        GameSession session = createLobby();
        gamePhaseScheduler.schedulePlayingTransition("ABCD", Instant.now(), () -> {
        });

        // when
        playerPresenceService.leaveGame("ABCD", session.host().playerId(), session.host().secret());
        playerPresenceService.leaveGame("ABCD", session.guest1().playerId(), session.guest1().secret());
        playerPresenceService.leaveGame("ABCD", session.guest2().playerId(), session.guest2().secret());

        // then
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(scheduledPlayingTransition).cancel(false);
    }

    @Test
    @DisplayName("존재하지 않는 방에서 나가면 RoomNotFoundException을 던진다.")
    void leaveGame_없는_방이면_예외를_던진다() {
        assertThatThrownBy(() -> playerPresenceService.leaveGame("ZZZZ", "player-id", "secret"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어가 나가면 PlayerNotFoundException을 던진다.")
    void leaveGame_방에_없는_플레이어면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> playerPresenceService.leaveGame("ABCD", "unknown-player-id", "secret"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("secret이 일치하지 않으면 플레이어를 제거하지 않는다.")
    void leaveGame_secret이_일치하지_않으면_예외를_던진다() {
        // given
        GameSession session = createLobby();

        // when & then
        assertThatThrownBy(() -> playerPresenceService.leaveGame("ABCD", session.guest1().playerId(), "wrong-secret"))
                .isInstanceOf(UnauthorizedPlayerException.class)
                .hasMessage("본인만 퇴장할 수 있습니다.");
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(session.guest1().playerId()), "대상 플레이어가 방에 남아 있어야 한다");
    }

    @Test
    @DisplayName("삭제 예약 중 명시적으로 퇴장하면 즉시 제거하고 예약을 취소한다.")
    void leaveGame_삭제_예약을_취소하고_즉시_퇴장시킨다() {
        // given
        GameSession session = createLobby();
        playerPresenceService.handleDisconnect("ABCD", session.guest1().playerId());
        Runnable removal = captureScheduledRemoval();

        // when
        playerPresenceService.leaveGame("ABCD", session.guest1().playerId(), session.guest1().secret());
        removal.run();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> !room.hasPlayer(session.guest1().playerId()), "참가자가 제거된 상태여야 한다");
        verify(scheduledRemoval).cancel(false);
    }

    @Test
    @DisplayName("연결이 끊겨도 유예 시간 동안은 참가자를 제거하지 않고 삭제를 예약한다.")
    void handleDisconnect_직후에는_참가자를_제거하지_않고_삭제를_예약한다() {
        // given
        GameSession session = createLobby();

        // when
        playerPresenceService.handleDisconnect("ABCD", session.guest1().playerId());

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(session.guest1().playerId()), "참가자가 방에 남아 있어야 한다");
        verify(disconnectGraceScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("유예 시간이 지나면 예약된 작업이 참가자를 제거하고 스냅샷을 브로드캐스트한다.")
    void handleDisconnect_유예가_지나면_참가자를_제거하고_브로드캐스트한다() {
        // given
        GameSession session = createLobby();
        playerPresenceService.handleDisconnect("ABCD", session.guest1().playerId());

        // when
        captureScheduledRemoval().run();

        // then
        LobbySnapshot snapshot = captureLastLobbyBroadcast(3);
        assertThat(snapshot.players()).extracting(player -> player.id())
                .containsExactly(session.host().playerId(), session.guest2().playerId());
    }

    @Test
    @DisplayName("유예 시간이 지나 방이 비면 방을 삭제하고 게임 단계 예약을 취소한다.")
    void handleDisconnect_유예가_지나_방이_비면_방을_삭제한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        playerPresenceService.handleDisconnect("ABCD", created.playerId());

        // when
        captureScheduledRemoval().run();

        // then
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(gamePhaseScheduler).cancelAll("ABCD");
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("존재하지 않는 방의 연결 끊김은 예약 작업이 실행돼도 예외 없이 무시한다.")
    void handleDisconnect_없는_방이면_무시한다() {
        assertThatCode(() -> {
            playerPresenceService.handleDisconnect("ZZZZ", "player-id");
            captureScheduledRemoval().run();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("방에 없는 플레이어의 연결 끊김은 예약 작업이 실행돼도 무시한다.")
    void handleDisconnect_방에_없는_플레이어면_무시한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");
        playerPresenceService.handleDisconnect("ABCD", "unknown-player-id");

        // when
        captureScheduledRemoval().run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("진행 중 참가자가 나가도 방 상태와 게임 단계 예약을 유지한다.")
    void leaveGame_진행_중_참가자가_나가도_방_상태와_타이머를_유지한다() {
        // given
        GameSession session = createGeneratingRoom();
        clearInvocations(messagingTemplate, gamePhaseScheduler);

        // when
        playerPresenceService.leaveGame("ABCD", session.guest2().playerId(), session.guest2().secret());

        // then
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.GENERATING);
            softly.assertThat(room.hasPlayer(session.guest2().playerId())).isFalse();
            softly.assertThat(room.getPromptEntries()).hasSize(2);
        });
        verifyNoInteractions(messagingTemplate, gamePhaseScheduler);
    }

    @Test
    @DisplayName("진행 중 참가자의 연결 종료가 확정돼도 방 상태와 게임 단계 예약을 유지한다.")
    void handleDisconnect_진행_중_참가자가_제거돼도_방_상태와_타이머를_유지한다() {
        // given
        GameSession session = createGeneratingRoom();
        playerPresenceService.handleDisconnect("ABCD", session.guest2().playerId());
        clearInvocations(messagingTemplate, gamePhaseScheduler);

        // when
        captureScheduledRemoval().run();

        // then
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.GENERATING);
            softly.assertThat(room.hasPlayer(session.guest2().playerId())).isFalse();
            softly.assertThat(room.getPromptEntries()).hasSize(2);
        });
        verifyNoInteractions(messagingTemplate, gamePhaseScheduler);
    }

    @Test
    @DisplayName("연결 끊김 후 삭제 예약을 취소하면 예약된 future를 취소한다.")
    void cancelPendingRemoval_예약된_future를_취소한다() {
        // given
        GameSession session = createLobby();
        playerPresenceService.handleDisconnect("ABCD", session.host().playerId());

        // when
        playerPresenceService.cancelPendingRemoval("ABCD", session.host().playerId());

        // then
        verify(scheduledRemoval).cancel(false);
    }

    @Test
    @DisplayName("삭제 예약을 취소하면 이미 시작된 예약 작업이 실행돼도 참가자를 제거하지 않는다.")
    void cancelPendingRemoval_취소된_예약_작업은_참가자를_제거하지_않는다() {
        // given
        GameSession session = createLobby();
        playerPresenceService.handleDisconnect("ABCD", session.host().playerId());
        Runnable removal = captureScheduledRemoval();
        playerPresenceService.cancelPendingRemoval("ABCD", session.host().playerId());

        // when
        removal.run();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(session.host().playerId()), "참가자가 방에 남아 있어야 한다");
    }

    @Test
    @DisplayName("삭제 예약이 없어도 취소 요청을 예외 없이 무시한다.")
    void cancelPendingRemoval_예약이_없으면_무시한다() {
        assertThatCode(() -> playerPresenceService.cancelPendingRemoval("ABCD", "player-id"))
                .doesNotThrowAnyException();
    }

    private GameSession createLobby() {
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse host = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        return new GameSession(host, guest1, guest2);
    }

    private GameSession createGeneratingRoom() {
        GameSession session = createLobby();
        gameLobbyService.changeReady("ABCD", session.guest1().playerId(), true);
        gameLobbyService.changeReady("ABCD", session.guest2().playerId(), true);
        gameRegistry.find("ABCD").orElseThrow()
                .start(session.host().playerId(), Instant.now(), Duration.ofSeconds(30));
        return session;
    }

    private Runnable captureScheduledRemoval() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(disconnectGraceScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    private LobbySnapshot captureLastLobbyBroadcast(int expectedBroadcastCount) {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, times(expectedBroadcastCount))
                .convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getValue();
        assertThat(message.type()).isEqualTo(RoomMessageType.LOBBY_SNAPSHOT);
        return (LobbySnapshot) message.payload();
    }

    private record GameSession(
            CreateGameResponse host,
            JoinGameResponse guest1,
            JoinGameResponse guest2
    ) {
    }
}
