package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.igmo.domain.AutoPromptPrefix;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntry;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.service.exception.GeminiResponseException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.ImageGenerationResult;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PlayerView;
import com.igmo.web.dto.PromptEntryView;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class GameServiceTest {

    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final TaskScheduler disconnectGraceScheduler = mock(TaskScheduler.class);
    private final TaskScheduler promptDeadlineScheduler = mock(TaskScheduler.class);
    private final TaskScheduler imageGenerationCompletionScheduler = mock(TaskScheduler.class);
    private final ImageGenerationClient imageGenerationClient = mock(ImageGenerationClient.class);
    private final Logger gameServiceLogger = (Logger) LoggerFactory.getLogger(GameService.class);
    private ListAppender<ILoggingEvent> imageGenerationLogAppender;
    private Runnable imageGenerationTask;
    private final Executor imageGenerationExecutor = command -> imageGenerationTask = command;
    private final ScheduledFuture<?> scheduledRemoval = mock(ScheduledFuture.class);
    private final ScheduledFuture<?> scheduledPromptExpiration = mock(ScheduledFuture.class);
    private final ScheduledFuture<?> scheduledPlayingTransition = mock(ScheduledFuture.class);
    private final GameService gameService =
            new GameService(
                    gameRegistry,
                    roomCodeGenerator,
                    messagingTemplate,
                    disconnectGraceScheduler,
                    promptDeadlineScheduler,
                    imageGenerationCompletionScheduler,
                    imageGenerationClient,
                    imageGenerationExecutor
            );

    @BeforeEach
    void 스케줄러가_예약_future를_반환하도록_설정한다() {
        imageGenerationTask = null;
        ReflectionTestUtils.setField(gameService, "disconnectGrace", Duration.ofSeconds(3));
        ReflectionTestUtils.setField(gameService, "promptDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(gameService, "imageGenerationCompletionDelay", Duration.ofSeconds(3));
        given(disconnectGraceScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledRemoval);
        given(promptDeadlineScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPromptExpiration);
        given(imageGenerationCompletionScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPlayingTransition);
        imageGenerationLogAppender = new ListAppender<>();
        imageGenerationLogAppender.start();
        gameServiceLogger.addAppender(imageGenerationLogAppender);
    }

    @AfterEach
    void 이미지_생성_로그_appender를_제거한다() {
        gameServiceLogger.detachAppender(imageGenerationLogAppender);
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
        verify(messagingTemplate).convertAndSend("/topic/rooms/ABCD", RoomMessage.lobbySnapshot(response.snapshot()));
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
                .convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
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
    @DisplayName("방장이 시작하면 GENERATING 단계로 진행한 스냅샷을 브로드캐스트한다.")
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
        List<RoomMessage> messages = captureRoomBroadcasts(5);
        RoomMessage lastMessage = messages.getLast();
        PromptSubmissionSnapshot promptSnapshot = (PromptSubmissionSnapshot) lastMessage.payload();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(lastMessage.type()).isEqualTo(RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT);
            softly.assertThat(messages)
                    .filteredOn(message -> message.type() == RoomMessageType.LOBBY_SNAPSHOT)
                    .hasSize(4);
            softly.assertThat(promptSnapshot.phase()).isEqualTo(GamePhase.GENERATING);
            softly.assertThat(promptSnapshot.promptStartedAt()).isNotNull();
            softly.assertThat(promptSnapshot.promptDeadline())
                    .isEqualTo(promptSnapshot.promptStartedAt().plusSeconds(30));
            softly.assertThat(promptSnapshot.promptEntries())
                    .extracting(promptEntry -> promptEntry.player().id(),
                            PromptEntryView::submitted)
                    .containsExactly(
                            tuple(created.playerId(), false),
                            tuple(guest1.playerId(), false),
                            tuple(guest2.playerId(), false)
                    );
        });
        verify(promptDeadlineScheduler).schedule(any(Runnable.class), eq(promptSnapshot.promptDeadline()));
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
    @DisplayName("GENERATING 단계에서 프롬프트를 제출하면 플레이어의 프롬프트 상태를 저장하고 스냅샷을 브로드캐스트한다.")
    void submitPrompt_GENERATING_단계이면_프롬프트를_저장하고_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", created.playerId());

        // when
        gameService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면");

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        PromptSubmissionSnapshot snapshot = capturePromptSubmissionBroadcast();
        PromptEntryView promptEntryView = findPromptEntryView(snapshot, guest1.playerId());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getPrompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(entry.getSubmittedAt()).isNotNull();
            softly.assertThat(snapshot.roomCode()).isEqualTo("ABCD");
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.GENERATING);
            softly.assertThat(snapshot.promptStartedAt()).isNotNull();
            softly.assertThat(snapshot.promptDeadline()).isEqualTo(snapshot.promptStartedAt().plusSeconds(30));
            softly.assertThat(snapshot.promptEntries()).hasSize(3);
            softly.assertThat(snapshot.promptEntries())
                    .extracting(promptEntry -> promptEntry.player().id(),
                            PromptEntryView::submitted)
                    .containsExactly(
                            tuple(created.playerId(), false),
                            tuple(guest1.playerId(), true),
                            tuple(guest2.playerId(), false)
                    );
            softly.assertThat(promptEntryView.player().id()).isEqualTo(guest1.playerId());
            softly.assertThat(promptEntryView.player().nickname()).isEqualTo("참가자1");
            softly.assertThat(promptEntryView.submitted()).isTrue();
            softly.assertThat(imageGenerationTask).isNotNull();
        });
    }

    @Test
    @DisplayName("이미지 생성이 성공하면 이미지 URL을 개인 전송하고 전체 상태를 브로드캐스트한다.")
    void imageGeneration_성공하면_이미지_URL을_개인_전송하고_전체_상태를_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        given(imageGenerationClient.generate("고양이가 피아노를 치는 장면"))
                .willReturn("https://cdn.example.com/prompt-1.png");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", created.playerId());
        gameService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        runImageGenerationTask();

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationResult result = captureImageGenerationResult(guest1.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/prompt-1.png");
            softly.assertThat(result.roomCode()).isEqualTo("ABCD");
            softly.assertThat(result.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(result.prompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(result.imageUrl()).isEqualTo("https://cdn.example.com/prompt-1.png");
            softly.assertThat(lastLogMessage("이미지 생성 완료"))
                    .contains("roomCode=ABCD", "playerId=", "durationMs=");
        });
        verify(imageGenerationClient).generate("고양이가 피아노를 치는 장면");
        PromptSubmissionSnapshot snapshot = captureLastPromptSubmissionBroadcast();
        assertThat(snapshot.promptEntries())
                .filteredOn(promptEntry -> promptEntry.player().id().equals(guest1.playerId()))
                .singleElement()
                .extracting(PromptEntryView::submitted)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("마지막 이미지 생성 전에는 PLAYING 전환을 예약하지 않는다.")
    void imageGeneration_마지막_이미지_생성_전에는_PLAYING_전환을_예약하지_않는다() {
        // given
        GameSession session = startGeneratingGame();

        // when
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(session.guest1().playerId(), "참가자1 프롬프트");

        // then
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("마지막 이미지 생성 시 3초 후 PLAYING 전환을 예약한다.")
    void imageGeneration_마지막_이미지_생성시_3초_후_PLAYING_전환을_예약한다() {
        // given
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(session.guest1().playerId(), "참가자1 프롬프트");

        // when
        Instant before = Instant.now();
        submitPromptAndCompleteImage(session.guest2().playerId(), "참가자2 프롬프트");
        Instant after = Instant.now();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .extracting(GameRoom::getPhase)
                .isEqualTo(GamePhase.GENERATING);
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), scheduledAt.capture());
        assertThat(scheduledAt.getValue()).isBetween(before.plusSeconds(3), after.plusSeconds(3));
    }

    @Test
    @DisplayName("예약된 PLAYING 전환 작업이 실행되면 phase를 PLAYING으로 변경한다.")
    void imageGeneration_예약된_PLAYING_전환_작업이_실행되면_phase를_PLAYING으로_변경한다() {
        // given
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(session.guest1().playerId(), "참가자1 프롬프트");
        submitPromptAndCompleteImage(session.guest2().playerId(), "참가자2 프롬프트");
        Runnable transition = captureScheduledPlayingTransition();

        // when
        clearInvocations(messagingTemplate);
        transition.run();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .extracting(GameRoom::getPhase)
                .isEqualTo(GamePhase.PLAYING);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("진행 중 참가자가 정상 퇴장하면 방을 로비로 되돌린다.")
    void leaveGame_진행_중_참가자가_나가면_로비로_되돌린다() {
        // given
        GameSession session = startGeneratingGame();
        clearInvocations(messagingTemplate);

        // when
        gameService.leaveGame("ABCD", session.guest2().playerId(), session.guest2().secret());

        // then
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        LobbySnapshot snapshot = captureLastLobbyBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.LOBBY);
            softly.assertThat(room.getPromptEntries()).isEmpty();
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.LOBBY);
            softly.assertThat(snapshot.players())
                    .extracting(PlayerView::id, PlayerView::ready)
                    .containsExactly(
                            tuple(session.host().playerId(), false),
                            tuple(session.guest1().playerId(), false)
                    );
        });
        verify(scheduledPromptExpiration).cancel(false);
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("진행 중 참가자의 연결 종료가 확정되면 방을 로비로 되돌린다.")
    void handleDisconnect_진행_중_참가자가_제거되면_로비로_되돌린다() {
        // given
        GameSession session = startGeneratingGame();
        gameService.handleDisconnect("ABCD", session.guest2().playerId());
        clearInvocations(messagingTemplate);

        // when
        captureScheduledRemoval().run();

        // then
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        LobbySnapshot snapshot = captureLastLobbyBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.LOBBY);
            softly.assertThat(room.getPromptEntries()).isEmpty();
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.LOBBY);
        });
        verify(scheduledPromptExpiration).cancel(false);
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("로비 복귀 후 완료된 이미지 생성 결과는 무시한다.")
    void imageGeneration_로비_복귀_후_완료된_결과는_무시한다() {
        // given
        GameSession session = startGeneratingGame();
        gameService.submitPrompt("ABCD", session.host().playerId(), "호스트 프롬프트");
        gameService.leaveGame("ABCD", session.guest2().playerId(), session.guest2().secret());
        clearInvocations(messagingTemplate);

        // when
        runImageGenerationTask();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .extracting(GameRoom::getPhase)
                .isEqualTo(GamePhase.LOBBY);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("마지막 이미지 생성 후 방이 삭제되면 PLAYING 전환 예약을 취소한다.")
    void imageGeneration_방이_삭제되면_PLAYING_전환_예약을_취소한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        given(imageGenerationClient.generate(any()))
                .willReturn(
                        "https://cdn.example.com/host.png",
                        "https://cdn.example.com/guest-1.png",
                        "https://cdn.example.com/guest-2.png");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", created.playerId());
        gameService.submitPrompt("ABCD", created.playerId(), "호스트 프롬프트");
        runImageGenerationTask();
        gameService.submitPrompt("ABCD", guest1.playerId(), "참가자1 프롬프트");
        runImageGenerationTask();
        gameService.submitPrompt("ABCD", guest2.playerId(), "참가자2 프롬프트");
        runImageGenerationTask();
        Runnable transition = captureScheduledPlayingTransition();

        // when
        gameService.leaveGame("ABCD", created.playerId(), created.secret());
        gameService.leaveGame("ABCD", guest1.playerId(), guest1.secret());
        gameService.leaveGame("ABCD", guest2.playerId(), guest2.secret());
        clearInvocations(messagingTemplate);
        transition.run();

        // then
        verify(scheduledPlayingTransition).cancel(false);
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("이미지 생성이 실패하면 개인 실패 결과를 전송하고 전체 상태를 브로드캐스트한다.")
    void imageGeneration_실패하면_개인_실패_결과를_전송하고_전체_상태를_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        given(imageGenerationClient.generate("고양이가 피아노를 치는 장면"))
                .willThrow(new GeminiResponseException(
                        "Gemini 응답에 이미지 데이터가 없습니다.",
                        List.of("text"),
                        200,
                        "gemini-3.1-flash-image",
                        "2K"));
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", created.playerId());
        gameService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        runImageGenerationTask();

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationResult result = captureImageGenerationResult(guest1.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.FAILED);
            softly.assertThat(entry.getImageUrl()).isNull();
            softly.assertThat(result.roomCode()).isEqualTo("ABCD");
            softly.assertThat(result.status()).isEqualTo(PromptEntryStatus.FAILED);
            softly.assertThat(result.prompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(result.imageUrl()).isNull();
            softly.assertThat(lastLogMessage("이미지 생성 실패"))
                    .contains("roomCode=ABCD", "playerId=", "durationMs=")
                    .contains("reason=Gemini 응답에 이미지 데이터가 없습니다.");
        });
        verify(imageGenerationClient).generate("고양이가 피아노를 치는 장면");
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        PromptSubmissionSnapshot snapshot = captureLastPromptSubmissionBroadcast();
        assertThat(snapshot.promptEntries())
                .filteredOn(promptEntry -> promptEntry.player().id().equals(guest1.playerId()))
                .singleElement()
                .extracting(PromptEntryView::submitted)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("프롬프트 마감 시 미제출 참가자에게 자동 프롬프트를 제출하고 이미지 생성을 시작한다.")
    void promptExpiration_미제출_참가자에게_자동_프롬프트를_제출하고_이미지_생성을_시작한다() {
        // given
        GameSession session = startGeneratingGame();
        gameService.submitPrompt("ABCD", session.host().playerId(), "호스트 프롬프트");
        gameService.submitPrompt("ABCD", session.guest1().playerId(), "참가자1 프롬프트");
        clearInvocations(messagingTemplate);

        // when
        captureScheduledPromptExpiration().run();

        // then
        PromptEntry autoSubmittedEntry = findPromptEntry("ABCD", session.guest2().playerId());
        PromptSubmissionSnapshot expirationSnapshot = captureLastPromptSubmissionBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(autoSubmittedEntry.getPrompt()).isIn(autoPromptCandidates("참가자2"));
            softly.assertThat(autoSubmittedEntry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(autoSubmittedEntry.getSubmittedAt()).isEqualTo(
                    gameRegistry.find("ABCD").orElseThrow().getPromptDeadline());
            softly.assertThat(findPromptEntryView(expirationSnapshot, session.guest2().playerId()).submitted()).isTrue();
            softly.assertThat(imageGenerationTask).isNotNull();
        });

        runImageGenerationTask();

        verify(imageGenerationClient).generate(autoSubmittedEntry.getPrompt());
        assertThat(autoSubmittedEntry.getStatus()).isEqualTo(PromptEntryStatus.READY);
    }

    @Test
    @DisplayName("존재하지 않는 방에 프롬프트를 제출하면 RoomNotFoundException을 던진다.")
    void submitPrompt_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gameService.submitPrompt("ZZZZ", "player-id", "프롬프트"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어가 프롬프트를 제출하면 PlayerNotFoundException을 던진다.")
    void submitPrompt_방에_없는_플레이어이면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameService.submitPrompt("ABCD", "unknown-player-id", "프롬프트"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("GENERATING 단계가 아니면 프롬프트 제출 시 PromptSubmissionNotAllowedException을 던진다.")
    void submitPrompt_GENERATING_단계가_아니면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gameService.submitPrompt("ABCD", created.playerId(), "프롬프트"))
                .isInstanceOf(PromptSubmissionNotAllowedException.class)
                .hasMessage("프롬프트를 제출할 수 있는 단계가 아닙니다.");
    }

    @Test
    @DisplayName("이미 제출한 플레이어가 다시 제출하면 DuplicatePromptSubmissionException을 던진다.")
    void submitPrompt_이미_제출한_플레이어이면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", created.playerId());
        gameService.submitPrompt("ABCD", guest1.playerId(), "첫 번째 프롬프트");

        // when & then
        assertThatThrownBy(() -> gameService.submitPrompt("ABCD", guest1.playerId(), "두 번째 프롬프트"))
                .isInstanceOf(DuplicatePromptSubmissionException.class)
                .hasMessage("이미 프롬프트를 제출했습니다.");
    }

    @Test
    @DisplayName("프롬프트 마감 이후 제출하면 PromptSubmissionExpiredException을 전파한다.")
    void submitPrompt_마감_이후이면_예외를_전파한다() {
        // given
        ReflectionTestUtils.setField(gameService, "promptDuration", Duration.ofMillis(-1));
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", created.playerId());

        // when & then
        assertThatThrownBy(() -> gameService.submitPrompt("ABCD", guest1.playerId(), "늦은 프롬프트"))
                .isInstanceOf(PromptSubmissionExpiredException.class)
                .hasMessage("프롬프트 제출 시간이 만료되었습니다.");
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

    private GameSession startGeneratingGame() {
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        given(imageGenerationClient.generate(any()))
                .willReturn(
                        "https://cdn.example.com/host.png",
                        "https://cdn.example.com/guest-1.png",
                        "https://cdn.example.com/guest-2.png");
        CreateGameResponse host = gameService.createGame("호스트");
        JoinGameResponse guest1 = gameService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameService.joinGame("ABCD", "참가자2");
        gameService.changeReady("ABCD", guest1.playerId(), true);
        gameService.changeReady("ABCD", guest2.playerId(), true);
        gameService.startGame("ABCD", host.playerId());
        return new GameSession(host, guest1, guest2);
    }

    private void submitPromptAndCompleteImage(String playerId, String prompt) {
        gameService.submitPrompt("ABCD", playerId, prompt);
        runImageGenerationTask();
    }

    private void runImageGenerationTask() {
        assertThat(imageGenerationTask).isNotNull();
        imageGenerationTask.run();
    }

    private Runnable captureScheduledPromptExpiration() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(promptDeadlineScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    private Runnable captureScheduledPlayingTransition() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(imageGenerationCompletionScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    private LobbySnapshot captureLastLobbyBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.LOBBY_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (LobbySnapshot) message.payload();
    }

    private List<RoomMessage> captureRoomBroadcasts(int expectedBroadcastCount) {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, times(expectedBroadcastCount))
                .convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        return captor.getAllValues();
    }

    private LobbySnapshot captureLastBroadcast(int expectedBroadcastCount) {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, times(expectedBroadcastCount))
                .convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getValue();
        assertThat(message.type()).isEqualTo(RoomMessageType.LOBBY_SNAPSHOT);
        return (LobbySnapshot) message.payload();
    }

    private PromptSubmissionSnapshot capturePromptSubmissionBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (PromptSubmissionSnapshot) message.payload();
    }

    private PromptSubmissionSnapshot captureLastPromptSubmissionBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (PromptSubmissionSnapshot) message.payload();
    }

    private ImageGenerationResult captureImageGenerationResult(String playerId) {
        ArgumentCaptor<ImageGenerationResult> captor = ArgumentCaptor.forClass(ImageGenerationResult.class);
        verify(messagingTemplate).convertAndSendToUser(eq(playerId), eq("/queue/image-generation"), captor.capture());
        return captor.getValue();
    }

    private String lastLogMessage(String messagePrefix) {
        return imageGenerationLogAppender.list.stream()
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().startsWith(messagePrefix))
                .reduce((previous, current) -> current)
                .orElseThrow()
                .getFormattedMessage();
    }

    private record GameSession(
            CreateGameResponse host,
            JoinGameResponse guest1,
            JoinGameResponse guest2
    ) {
    }

    private PromptEntry findPromptEntry(String code, String playerId) {
        return gameRegistry.find(code)
                .orElseThrow()
                .getPromptEntries().stream()
                .filter(entry -> entry.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private PromptEntryView findPromptEntryView(PromptSubmissionSnapshot snapshot, String playerId) {
        return snapshot.promptEntries().stream()
                .filter(entry -> entry.player().id().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private List<String> autoPromptCandidates(String nickname) {
        return Arrays.stream(AutoPromptPrefix.values())
                .map(prefix -> prefix.value() + " " + nickname)
                .toList();
    }
}
