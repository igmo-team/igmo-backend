package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.AutoPromptPrefix;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntry;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.SamplePrompt;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.monitoring.GameMetrics;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.imagegeneration.exception.GeminiResponseException;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.GameResultSnapshot;
import com.igmo.web.dto.GuessEntryView;
import com.igmo.web.dto.ImageGenerationResult;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.PromptEntryView;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteEntryView;
import com.igmo.web.dto.VoteOptionView;
import com.igmo.web.dto.VoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class GamePhaseServiceTest {

    private static final String SAMPLE_PROMPTS_JSON = """
            [
              { "prompt": "샘플 프롬프트 1", "imageUrl": "https://cdn.example.com/samples/1.png" },
              { "prompt": "샘플 프롬프트 2", "imageUrl": "https://cdn.example.com/samples/2.png" },
              { "prompt": "샘플 프롬프트 3", "imageUrl": "https://cdn.example.com/samples/3.png" }
            ]
            """;

    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final TaskScheduler gamePhaseDeadlineScheduler = mock(TaskScheduler.class);
    private final TaskScheduler imageGenerationCompletionScheduler = mock(TaskScheduler.class);
    private final ImageGenerator imageGenerator = mock(ImageGenerator.class);
    private final ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);
    private Runnable imageGenerationTask;
    private final Executor imageGenerationExecutor = command -> imageGenerationTask = command;
    private final ScheduledFuture<?> scheduledPromptExpiration = mock(ScheduledFuture.class);
    private final ScheduledFuture<?> scheduledPlayingTransition = mock(ScheduledFuture.class);
    private final GameRoomRepository gameRoomRepository = new GameRoomRepository(gameRegistry);
    private final GameEventPublisher eventPublisher = new GameEventPublisher(messagingTemplate, gameMetrics);
    private final GamePhaseScheduler gamePhaseScheduler =
            new GamePhaseScheduler(gamePhaseDeadlineScheduler, imageGenerationCompletionScheduler);
    private final GameLobbyService gameLobbyService =
            new GameLobbyService(gameRoomRepository, roomCodeGenerator, eventPublisher);
    private final ImageGenerationService imageGenerationService =
            new ImageGenerationService(
                    imageGenerator, imageStorageClient, gameMetrics, imageGenerationExecutor, "gemini-3.1-flash-image", "2K");
    private final SamplePromptProvider samplePromptProvider =
            new SamplePromptProvider(new ObjectMapper(), SAMPLE_PROMPTS_JSON, "test");
    private final GamePhaseService gamePhaseService =
            new GamePhaseService(
                    gameRoomRepository,
                    gamePhaseScheduler,
                    eventPublisher,
                    imageGenerationService,
                    samplePromptProvider);

    @BeforeEach
    void 게임_단계_전환_스케줄러를_설정한다() {
        imageGenerationTask = null;
        ReflectionTestUtils.setField(gamePhaseService, "promptDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(gamePhaseService, "guessDuration", Duration.ofSeconds(60));
        ReflectionTestUtils.setField(gamePhaseService, "voteDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(gamePhaseService, "resultDuration", Duration.ofSeconds(10));
        ReflectionTestUtils.setField(
                gamePhaseService,
                "imageGenerationCompletionDelay",
                Duration.ofSeconds(3));
        given(gamePhaseDeadlineScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPromptExpiration);
        given(imageGenerationCompletionScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPlayingTransition);
    }


    @Test
    @DisplayName("방장이 시작하면 GENERATING 단계로 진행한 스냅샷을 브로드캐스트한다.")
    void startGame_방장이_시작하면_다음_단계_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);

        // when
        gamePhaseService.startGame("ABCD", created.playerId());

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
        verify(gamePhaseDeadlineScheduler).schedule(any(Runnable.class), eq(promptSnapshot.promptDeadline()));
    }

    @Test
    @DisplayName("존재하지 않는 방을 시작하면 RoomNotFoundException을 던진다.")
    void startGame_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gamePhaseService.startGame("ZZZZ", "player-id"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방장이 아닌 참가자가 시작하면 도메인의 NotHostException을 전파한다.")
    void startGame_방장이_아니면_예외를_전파한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        gameLobbyService.joinGame("ABCD", "참가자2");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.startGame("ABCD", guest1.playerId()))
                .isInstanceOf(NotHostException.class)
                .hasMessage("방장만 게임을 시작할 수 있습니다.");
    }

    @Test
    @DisplayName("GENERATING 단계에서 프롬프트를 제출하면 플레이어의 프롬프트 상태를 저장하고 스냅샷을 브로드캐스트한다.")
    void submitPrompt_GENERATING_단계이면_프롬프트를_저장하고_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());

        // when
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면");

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
        givenGeneratedImage("고양이가 피아노를 치는 장면", "https://cdn.example.com/prompt-1.png");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면");
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
        });
        verifyGeneratedImage("고양이가 피아노를 치는 장면");
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
        RoundSnapshot snapshot = captureRoundSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gameRegistry.find("ABCD")).get()
                    .extracting(GameRoom::getPhase)
                    .isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.roundNumber()).isEqualTo(1);
            softly.assertThat(snapshot.totalRoundCount()).isEqualTo(3);
            softly.assertThat(snapshot.questioner().id()).isEqualTo(session.host().playerId());
            softly.assertThat(snapshot.imageUrl()).isEqualTo("https://cdn.example.com/host.png");
            softly.assertThat(snapshot.guessDeadline()).isNotNull();
            softly.assertThat(snapshot.guessEntries())
                    .extracting(GuessEntryView::submitted)
                    .containsOnly(false);
        });
        verify(messagingTemplate, times(1))
                .convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
        verify(gamePhaseDeadlineScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }



    @Test
    @DisplayName("이미지 생성이 실패하면 개인 실패 결과를 전송하고 전체 상태를 브로드캐스트한다.")
    void imageGeneration_실패하면_개인_실패_결과를_전송하고_전체_상태를_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        GeminiResponseException failure = new GeminiResponseException(
                "Gemini 응답이 이미지 대신 텍스트입니다.",
                List.of("text"),
                200,
                "gemini-3.1-flash-image",
                "2K");
        givenGenerationFailure("고양이가 피아노를 치는 장면", failure);
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면");
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
            softly.assertThat(result.errorMessage()).isEqualTo("Gemini 응답이 이미지 대신 텍스트입니다.");
        });
        verifyGeneratedImage("고양이가 피아노를 치는 장면");
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        PromptSubmissionSnapshot snapshot = captureLastPromptSubmissionBroadcast();
        assertThat(snapshot.promptEntries())
                .filteredOn(promptEntry -> promptEntry.player().id().equals(guest1.playerId()))
                .singleElement()
                .extracting(PromptEntryView::submitted)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("이미지 생성에 실패한 프롬프트는 마감 전 다시 제출할 수 있다.")
    void submitPrompt_이미지_생성에_실패하면_마감_전_다시_제출할_수_있다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGenerationFailure("실패한 프롬프트", new GeminiResponseException(
                "Gemini 응답이 이미지 대신 텍스트입니다.", List.of("text"), 200, "gemini-3.1-flash-image", "2K"));
        givenGeneratedImage("다시 입력한 프롬프트", "https://cdn.example.com/retried.png");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "실패한 프롬프트");
        runImageGenerationTask();
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "다시 입력한 프롬프트");
        runImageGenerationTask();

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationResult result = captureImageGenerationResult(guest1.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getPrompt()).isEqualTo("다시 입력한 프롬프트");
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/retried.png");
            softly.assertThat(result.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(result.errorMessage()).isNull();
        });
        verifyGeneratedImage("다시 입력한 프롬프트");
    }

    @Test
    @DisplayName("프롬프트 마감 작업이 실행되면 READY가 아닌 참가자를 샘플로 채우고 READY인 참가자는 유지한다.")
    void promptExpiration_READY가_아닌_참가자를_샘플로_채운다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        clearInvocations(messagingTemplate);
        imageGenerationTask = null;

        // when
        captureScheduledPromptExpiration().run();

        // then
        PromptEntry hostEntry = findPromptEntry("ABCD", session.host().playerId());
        PromptEntry guest1Entry = findPromptEntry("ABCD", session.guest1().playerId());
        PromptEntry guest2Entry = findPromptEntry("ABCD", session.guest2().playerId());
        List<SamplePrompt> pool = samplePromptProvider.getAll();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(hostEntry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(hostEntry.getPrompt()).isEqualTo("호스트 프롬프트");
            softly.assertThat(hostEntry.getImageUrl()).isEqualTo("https://cdn.example.com/host.png");
            softly.assertThat(guest1Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(pool).contains(new SamplePrompt(guest1Entry.getPrompt(), guest1Entry.getImageUrl()));
            softly.assertThat(guest2Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(pool).contains(new SamplePrompt(guest2Entry.getPrompt(), guest2Entry.getImageUrl()));
            softly.assertThat(imageGenerationTask).isNull();
        });
    }

    @Test
    @DisplayName("프롬프트 마감 시 샘플로 채운 참가자에게 개인 이미지 결과를 전송한다.")
    void promptExpiration_샘플로_채운_참가자에게_개인_이미지_결과를_전송한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        clearInvocations(messagingTemplate);

        // when
        captureScheduledPromptExpiration().run();

        // then
        ImageGenerationResult guest1Result = captureImageGenerationResult(session.guest1().playerId());
        ImageGenerationResult guest2Result = captureImageGenerationResult(session.guest2().playerId());
        List<SamplePrompt> pool = samplePromptProvider.getAll();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest1Result.roomCode()).isEqualTo("ABCD");
            softly.assertThat(guest1Result.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(pool).contains(new SamplePrompt(guest1Result.prompt(), guest1Result.imageUrl()));
            softly.assertThat(guest2Result.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(pool).contains(new SamplePrompt(guest2Result.prompt(), guest2Result.imageUrl()));
        });
    }

    @Test
    @DisplayName("프롬프트 마감으로 전원 READY가 되면 3초 후 PLAYING 전환을 예약한다.")
    void promptExpiration_전원_READY가_되면_PLAYING_전환을_예약한다() {
        // given
        startExpirationScenarioWithMissingImages();
        clearInvocations(messagingTemplate);

        // when
        Instant before = Instant.now();
        captureScheduledPromptExpiration().run();
        Instant after = Instant.now();

        // then
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), scheduledAt.capture());
        assertThat(scheduledAt.getValue()).isBetween(before.plusSeconds(3), after.plusSeconds(3));
    }

    @Test
    @DisplayName("모든 프롬프트를 제출해도 이미지가 준비되기 전에는 프롬프트 마감 작업을 취소하지 않는다.")
    void submitPrompt_이미지가_준비되기_전에는_프롬프트_마감을_취소하지_않는다() {
        // given
        GameSession session = startGeneratingGame();

        // when
        gamePhaseService.submitPrompt("ABCD", session.host().playerId(), "호스트 프롬프트");
        gamePhaseService.submitPrompt("ABCD", session.guest1().playerId(), "참가자1 프롬프트");
        gamePhaseService.submitPrompt("ABCD", session.guest2().playerId(), "참가자2 프롬프트");

        // then
        verify(scheduledPromptExpiration, never()).cancel(false);
    }

    @Test
    @DisplayName("전원 프롬프트 제출 후 일부 이미지 생성이 실패해도 마감 시 실패한 참가자를 샘플로 채워 전원 READY로 만든다.")
    void promptExpiration_이미지_생성_실패자를_샘플로_채운다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGeneratedImage("호스트 프롬프트", "https://cdn.example.com/host.png");
        givenGeneratedImage("참가자1 프롬프트", "https://cdn.example.com/guest-1.png");
        givenGenerationFailure("참가자2 프롬프트", new RuntimeException("이미지 생성 실패"));
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        submitPromptAndCompleteImage(created.playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(guest1.playerId(), "참가자1 프롬프트");
        submitPromptAndCompleteImage(guest2.playerId(), "참가자2 프롬프트");
        clearInvocations(messagingTemplate);

        // when
        captureScheduledPromptExpiration().run();

        // then
        PromptEntry guest2Entry = findPromptEntry("ABCD", guest2.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest2Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(samplePromptProvider.getAll())
                    .contains(new SamplePrompt(guest2Entry.getPrompt(), guest2Entry.getImageUrl()));
            softly.assertThat(gameRegistry.find("ABCD").orElseThrow().hasAllImagesGenerated()).isTrue();
        });
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("샘플로 채워진 뒤 도착한 실제 이미지 생성 결과는 개인 전송하지 않고 샘플을 유지한다.")
    void imageGeneration_샘플로_채워진_뒤_도착한_결과는_무시한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        Runnable lateGuest1Generation = imageGenerationTask;
        captureScheduledPromptExpiration().run();
        clearInvocations(messagingTemplate);

        // when
        lateGuest1Generation.run();

        // then
        PromptEntry guest1Entry = findPromptEntry("ABCD", session.guest1().playerId());
        assertThat(samplePromptProvider.getAll())
                .contains(new SamplePrompt(guest1Entry.getPrompt(), guest1Entry.getImageUrl()));
        verify(messagingTemplate, never())
                .convertAndSendToUser(eq(session.guest1().playerId()), eq("/queue/image-generation"), any());
    }

    @Test
    @DisplayName("존재하지 않는 방에 프롬프트를 제출하면 RoomNotFoundException을 던진다.")
    void submitPrompt_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ZZZZ", "player-id", "프롬프트"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어가 프롬프트를 제출하면 PlayerNotFoundException을 던진다.")
    void submitPrompt_방에_없는_플레이어이면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", "unknown-player-id", "프롬프트"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("GENERATING 단계가 아니면 프롬프트 제출 시 PromptSubmissionNotAllowedException을 던진다.")
    void submitPrompt_GENERATING_단계가_아니면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", created.playerId(), "프롬프트"))
                .isInstanceOf(PromptSubmissionNotAllowedException.class)
                .hasMessage("프롬프트를 제출할 수 있는 단계가 아닙니다.");
    }

    @Test
    @DisplayName("이미 제출한 플레이어가 다시 제출하면 DuplicatePromptSubmissionException을 던진다.")
    void submitPrompt_이미_제출한_플레이어이면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "첫 번째 프롬프트");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "두 번째 프롬프트"))
                .isInstanceOf(DuplicatePromptSubmissionException.class)
                .hasMessage("이미 프롬프트를 제출했습니다.");
    }

    @Test
    @DisplayName("프롬프트 마감 이후 제출하면 PromptSubmissionExpiredException을 전파한다.")
    void submitPrompt_마감_이후이면_예외를_전파한다() {
        // given
        ReflectionTestUtils.setField(gamePhaseService, "promptDuration", Duration.ofMillis(-1));
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "늦은 프롬프트"))
                .isInstanceOf(PromptSubmissionExpiredException.class)
                .hasMessage("프롬프트 제출 시간이 만료되었습니다.");
    }


    @Test
    @DisplayName("추측을 제출하면 제출 현황이 담긴 라운드 스냅샷을 브로드캐스트한다.")
    void submitGuess_추측을_제출하면_현황을_브로드캐스트한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");

        // then
        RoundSnapshot snapshot = captureRoundSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.guessEntries())
                    .extracting(entry -> entry.player().id(), GuessEntryView::submitted)
                    .containsExactly(
                            tuple(playerIds.get(1), true),
                            tuple(playerIds.get(2), false)
                    );
        });
    }

    @Test
    @DisplayName("출제자를 제외한 전원이 추측을 제출하면 VOTING 스냅샷을 브로드캐스트하고 마감 작업을 취소한다.")
    void submitGuess_전원이_제출하면_VOTING_스냅샷을_브로드캐스트하고_마감_작업을_취소한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");

        // then
        VoteSnapshot voteSnapshot = captureVoteSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(voteSnapshot.phase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(voteSnapshot.voteOptions()).hasSize(3);
            softly.assertThat(voteSnapshot.voteDeadline()).isNotNull();
        });
        verify(gamePhaseDeadlineScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
        verify(scheduledPromptExpiration, times(2)).cancel(false);
    }

    @Test
    @DisplayName("방에 없는 플레이어가 추측을 제출하면 PlayerNotFoundException을 던진다.")
    void submitGuess_방에_없는_플레이어면_예외를_던진다() {
        // given
        setUpRoomInPlaying();

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitGuess("ABCD", "unknown-player", "추측"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("추측 마감 작업이 실행되면 미제출자에게 자동 추측을 채워 VOTING 스냅샷을 브로드캐스트한다.")
    void guessDeadline_마감_작업이_실행되면_자동_추측을_채워_VOTING_스냅샷을_브로드캐스트한다() {
        // given
        setUpRoomWithImagesReady();
        captureScheduledPlayingTransition().run();
        Runnable guessExpiration = captureLastScheduledDeadline(2);
        clearInvocations(messagingTemplate);

        // when
        guessExpiration.run();

        // then
        VoteSnapshot voteSnapshot = captureVoteSnapshotBroadcast();
        List<String> optionTexts = voteSnapshot.voteOptions().stream()
                .map(VoteOptionView::text)
                .toList();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(voteSnapshot.phase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(voteSnapshot.voteOptions()).hasSize(3);
            softly.assertThat(optionTexts).anyMatch(text -> autoPromptCandidates("참가자1").contains(text));
            softly.assertThat(optionTexts).anyMatch(text -> autoPromptCandidates("참가자2").contains(text));
            softly.assertThat(voteSnapshot.voteEntries())
                    .extracting(VoteEntryView::voted)
                    .containsOnly(false);
        });
    }

    @Test
    @DisplayName("취소된 추측 마감 작업이 실행되면 아무것도 브로드캐스트하지 않는다.")
    void guessDeadline_취소된_마감_작업이_실행되면_무시한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        Runnable guessExpiration = captureLastScheduledDeadline(2);
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        guessExpiration.run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("투표를 제출하면 투표 현황 스냅샷을 브로드캐스트한다.")
    void submitVote_투표를_제출하면_현황을_브로드캐스트한다() {
        // given
        List<String> playerIds = setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);

        // then
        VoteSnapshot snapshot = captureVoteSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(snapshot.voteEntries())
                    .extracting(entry -> entry.player().id(), VoteEntryView::voted)
                    .containsExactly(
                            tuple(playerIds.get(1), true),
                            tuple(playerIds.get(2), false)
                    );
        });
    }

    @Test
    @DisplayName("출제자를 제외한 전원이 투표하면 RESULTS로 전환하고 마감 작업을 취소한다.")
    void submitVote_전원이_투표하면_RESULTS로_전환하고_마감_작업을_취소한다() {
        // given
        List<String> playerIds = setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitVote("ABCD", playerIds.get(2), answerOptionId);

        // then
        RoundResultSnapshot snapshot = captureRoundResultSnapshotBroadcast();
        assertThat(snapshot.phase()).isEqualTo(GamePhase.RESULTS);
        verify(scheduledPromptExpiration, times(3)).cancel(false);
    }

    @Test
    @DisplayName("방에 없는 플레이어가 투표를 제출하면 PlayerNotFoundException을 던진다.")
    void submitVote_방에_없는_플레이어면_예외를_던진다() {
        // given
        setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitVote("ABCD", "unknown-player", answerOptionId))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }

    @Test
    @DisplayName("투표 마감 작업이 실행되면 RESULTS로 전환한 스냅샷을 브로드캐스트한다.")
    void voteDeadline_마감_작업이_실행되면_RESULTS로_전환한다() {
        // given
        ReflectionTestUtils.setField(gamePhaseService, "voteDuration", Duration.ofMillis(-1));
        setUpRoomInVoting();
        Runnable voteExpiration = captureLastScheduledDeadline(3);
        clearInvocations(messagingTemplate);

        // when
        voteExpiration.run();

        // then
        RoundResultSnapshot snapshot = captureRoundResultSnapshotBroadcast();
        assertThat(snapshot.phase()).isEqualTo(GamePhase.RESULTS);
    }

    @Test
    @DisplayName("취소된 투표 마감 작업이 실행되면 아무것도 브로드캐스트하지 않는다.")
    void voteDeadline_취소된_마감_작업이_실행되면_무시한다() {
        // given
        List<String> playerIds = setUpRoomInVoting();
        Runnable voteExpiration = captureLastScheduledDeadline(3);
        String answerOptionId = findAnswerOptionId("ABCD");
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);
        gamePhaseService.submitVote("ABCD", playerIds.get(2), answerOptionId);
        clearInvocations(messagingTemplate);

        // when
        voteExpiration.run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    @Test
    @DisplayName("결과 확인 마감 작업이 실행되면 다음 라운드 스냅샷을 브로드캐스트하고 추측 마감을 예약한다.")
    void resultDeadline_마감_작업이_실행되면_다음_라운드로_넘어간다() {
        // given
        List<String> playerIds = setUpRoomInResults();
        Runnable resultExpiration = captureLastScheduledDeadline(4);
        clearInvocations(messagingTemplate);

        // when
        resultExpiration.run();

        // then
        RoundSnapshot snapshot = captureRoundSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.roundNumber()).isEqualTo(2);
            softly.assertThat(snapshot.questioner().id()).isEqualTo(playerIds.get(1));
        });
        verify(gamePhaseDeadlineScheduler, times(5)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("마지막 라운드의 결과 확인 마감 작업이 실행되면 게임 종료 스냅샷을 브로드캐스트한다.")
    void resultDeadline_마지막_라운드면_게임_종료_스냅샷을_브로드캐스트한다() {
        // given
        setUpRoomInResults();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "currentRoundIndex", room.getTotalRoundCount() - 1);
        Runnable resultExpiration = captureLastScheduledDeadline(4);
        clearInvocations(messagingTemplate);

        // when
        resultExpiration.run();

        // then
        GameResultSnapshot snapshot = captureGameResultSnapshotBroadcast();
        assertThat(snapshot.phase()).isEqualTo(GamePhase.ENDED);
    }

    @Test
    @DisplayName("취소된 결과 확인 마감 작업이 실행되면 아무것도 브로드캐스트하지 않는다.")
    void resultDeadline_취소된_마감_작업이_실행되면_무시한다() {
        // given
        setUpRoomInResults();
        Runnable resultExpiration = captureLastScheduledDeadline(4);
        resultExpiration.run();
        clearInvocations(messagingTemplate);

        // when
        resultExpiration.run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

    private List<String> setUpRoomInPlaying() {
        List<String> playerIds = setUpRoomWithImagesReady();
        captureScheduledPlayingTransition().run();
        return playerIds;
    }

    private List<String> setUpRoomWithImagesReady() {
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGeneratedImages(
                "https://cdn.example.com/host.png",
                "https://cdn.example.com/guest-1.png",
                "https://cdn.example.com/guest-2.png");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", created.playerId(), "호스트 프롬프트");
        runImageGenerationTask();
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "참가자1 프롬프트");
        runImageGenerationTask();
        gamePhaseService.submitPrompt("ABCD", guest2.playerId(), "참가자2 프롬프트");
        runImageGenerationTask();
        return List.of(created.playerId(), guest1.playerId(), guest2.playerId());
    }

    private List<String> setUpRoomInVoting() {
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");
        return playerIds;
    }

    private List<String> setUpRoomInResults() {
        List<String> playerIds = setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);
        gamePhaseService.submitVote("ABCD", playerIds.get(2), answerOptionId);
        return playerIds;
    }

    private String findAnswerOptionId(String code) {
        return gameRegistry.find(code)
                .orElseThrow()
                .getCurrentRound()
                .getAnswerEntry()
                .getPromptId();
    }

    private RoundSnapshot captureRoundSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.ROUND_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (RoundSnapshot) message.payload();
    }

    private VoteSnapshot captureVoteSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.VOTE_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (VoteSnapshot) message.payload();
    }

    private RoundResultSnapshot captureRoundResultSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.ROUND_RESULT_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (RoundResultSnapshot) message.payload();
    }

    private GameResultSnapshot captureGameResultSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.GAME_RESULT_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (GameResultSnapshot) message.payload();
    }

    private Runnable captureLastScheduledDeadline(int expectedScheduleCount) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(gamePhaseDeadlineScheduler, times(expectedScheduleCount)).schedule(captor.capture(), any(Instant.class));
        return captor.getAllValues().get(expectedScheduleCount - 1);
    }

    private GameSession startGeneratingGame() {
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGeneratedImages(
                "https://cdn.example.com/host.png",
                "https://cdn.example.com/guest-1.png",
                "https://cdn.example.com/guest-2.png");
        CreateGameResponse host = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", host.playerId());
        return new GameSession(host, guest1, guest2);
    }

    private void submitPromptAndCompleteImage(String playerId, String prompt) {
        gamePhaseService.submitPrompt("ABCD", playerId, prompt);
        runImageGenerationTask();
    }

    private void givenGeneratedImage(String prompt, String imageUrl) {
        byte[] image = prompt.getBytes(StandardCharsets.UTF_8);
        given(imageGenerator.generate(new ImageGenerationRequest(prompt, "gemini-3.1-flash-image", "2K")))
                .willReturn(new GeneratedImage(image, "image/jpeg"));
        given(imageStorageClient.store(image, "image/jpeg")).willReturn(imageUrl);
    }

    private void givenGenerationFailure(String prompt, RuntimeException exception) {
        given(imageGenerator.generate(new ImageGenerationRequest(prompt, "gemini-3.1-flash-image", "2K")))
                .willThrow(exception);
    }

    private void givenGeneratedImages(String... imageUrls) {
        AtomicInteger storedImageIndex = new AtomicInteger();
        given(imageGenerator.generate(any(ImageGenerationRequest.class)))
                .willReturn(new GeneratedImage("image".getBytes(StandardCharsets.UTF_8), "image/jpeg"));
        given(imageStorageClient.store(any(byte[].class), eq("image/jpeg")))
                .willAnswer(invocation -> imageUrls[storedImageIndex.getAndIncrement()]);
    }

    private void verifyGeneratedImage(String prompt) {
        verify(imageGenerator).generate(new ImageGenerationRequest(prompt, "gemini-3.1-flash-image", "2K"));
    }

    // host: READY(실제 이미지), 참가자1: GENERATING(제출했지만 이미지 미완료), 참가자2: WAITING(무제출)
    private GameSession startExpirationScenarioWithMissingImages() {
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        gamePhaseService.submitPrompt("ABCD", session.guest1().playerId(), "참가자1 프롬프트");
        return session;
    }

    private void runImageGenerationTask() {
        assertThat(imageGenerationTask).isNotNull();
        imageGenerationTask.run();
    }

    private Runnable captureScheduledPromptExpiration() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(gamePhaseDeadlineScheduler).schedule(captor.capture(), any(Instant.class));
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
