package com.igmo.service.phase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.AutoPromptPrefix;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameStartPolicy;
import com.igmo.domain.GuessSubmissionType;
import com.igmo.domain.PromptEntry;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.PromptSubmissionType;
import com.igmo.domain.SamplePrompt;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PerfectGuesserVoteNotAllowedException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import com.igmo.imagegeneration.exception.GeminiResponseException;
import com.igmo.monitoring.GameMetrics;
import com.igmo.service.GameDrainLifecycle;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.ImageGenerationService;
import com.igmo.service.ImageStorageClient;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.lobby.GameLobbyService;
import com.igmo.service.lobby.RoomCodeGenerator;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.LobbySnapshot;
import com.igmo.web.http.response.CreateGameResponse;
import com.igmo.web.http.response.JoinGameResponse;
import com.igmo.web.websocket.message.ImageGenerationEvent;
import com.igmo.web.websocket.message.OwnVoteOptionNotice;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.message.RoomMessageType;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import com.igmo.web.websocket.snapshot.GuessEntryView;
import com.igmo.web.websocket.snapshot.GuessSubmissionSnapshot;
import com.igmo.web.websocket.snapshot.GuessSubmissionStatus;
import com.igmo.web.websocket.snapshot.PromptEntryView;
import com.igmo.web.websocket.snapshot.PromptSubmissionSnapshot;
import com.igmo.web.websocket.snapshot.RoundResultSnapshot;
import com.igmo.web.websocket.snapshot.RoundSnapshot;
import com.igmo.web.websocket.snapshot.VoteDisabledReason;
import com.igmo.web.websocket.snapshot.VoteOptionView;
import com.igmo.web.websocket.snapshot.VoteSkippedReason;
import com.igmo.web.websocket.snapshot.VoteSkippedSnapshot;
import com.igmo.web.websocket.snapshot.VoteSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

abstract class GamePhaseServiceTestSupport {

    private static final String SAMPLE_PROMPTS_JSON = """
            [
              { "prompt": "샘플 프롬프트 1", "imageUrl": "https://cdn.example.com/samples/1.png" },
              { "prompt": "샘플 프롬프트 2", "imageUrl": "https://cdn.example.com/samples/2.png" },
              { "prompt": "샘플 프롬프트 3", "imageUrl": "https://cdn.example.com/samples/3.png" }
            ]
            """;

    protected final GameMetrics gameMetrics = mock(GameMetrics.class);
    protected final GameRegistry gameRegistry = new GameRegistry();
    protected final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    protected final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    protected final TaskScheduler gamePhaseDeadlineScheduler = mock(TaskScheduler.class);
    protected final TaskScheduler imageGenerationCompletionScheduler = mock(TaskScheduler.class);
    protected final ImageGenerator imageGenerator = mock(ImageGenerator.class);
    protected final ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);
    protected Runnable imageGenerationTask;
    protected final Executor imageGenerationExecutor = command -> imageGenerationTask = command;
    protected final ScheduledFuture<?> scheduledPromptExpiration = mock(ScheduledFuture.class);
    protected final ScheduledFuture<?> scheduledPlayingTransition = mock(ScheduledFuture.class);
    protected final GameRoomRepository gameRoomRepository = new GameRoomRepository(gameRegistry);
    protected final GameEventPublisher eventPublisher = new GameEventPublisher(messagingTemplate, gameMetrics);
    protected final GamePhaseScheduler gamePhaseScheduler =
            new GamePhaseScheduler(gamePhaseDeadlineScheduler, imageGenerationCompletionScheduler);
    protected final GamePhaseScheduler gameLobbyPhaseScheduler = mock(GamePhaseScheduler.class);
    protected final GameLobbyService gameLobbyService =
            new GameLobbyService(
                    gameRoomRepository,
                    roomCodeGenerator,
                    eventPublisher,
                    GameStartPolicy.standard(),
                    gameLobbyPhaseScheduler);
    protected final ImageGenerationService imageGenerationService =
            new ImageGenerationService(
                    imageGenerator, imageStorageClient, gameMetrics, imageGenerationExecutor, "gemini-3.1-flash-image",
                    "2K");
    protected final SamplePromptProvider samplePromptProvider =
            new SamplePromptProvider(new ObjectMapper(), SAMPLE_PROMPTS_JSON, "test");
    protected final GameDrainLifecycle gameDrainLifecycle = mock(GameDrainLifecycle.class);
    protected GamePhaseService gamePhaseService;
    protected PromptPhaseService promptPhaseService;
    protected GuessPhaseService guessPhaseService;
    protected VoteResultPhaseService voteResultPhaseService;
    protected final Logger gamePhaseLogger = (Logger) LoggerFactory.getLogger(GamePhaseService.class);
    protected ListAppender<ILoggingEvent> gamePhaseLogAppender;

    @BeforeEach
    void 게임_단계_전환_스케줄러를_설정한다() {
        gamePhaseLogAppender = new ListAppender<>();
        gamePhaseLogAppender.start();
        gamePhaseLogger.addAppender(gamePhaseLogAppender);
        imageGenerationTask = null;
        ReflectionTestUtils.setField(gameLobbyService, "lobbyDuration", Duration.ofMinutes(10));
        given(gamePhaseDeadlineScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPromptExpiration);
        given(imageGenerationCompletionScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledPlayingTransition);
        guessPhaseService = new GuessPhaseService(gameRoomRepository, gamePhaseScheduler, eventPublisher);
        voteResultPhaseService = new VoteResultPhaseService(
                gameRoomRepository,
                gamePhaseScheduler,
                eventPublisher,
                gameDrainLifecycle,
                guessPhaseService);
        promptPhaseService = new PromptPhaseService(
                gameRoomRepository,
                gamePhaseScheduler,
                eventPublisher,
                imageGenerationService,
                samplePromptProvider,
                guessPhaseService,
                voteResultPhaseService);
        gamePhaseService = new GamePhaseService(
                promptPhaseService,
                guessPhaseService,
                voteResultPhaseService);
        ReflectionTestUtils.setField(promptPhaseService, "promptDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(promptPhaseService, "guessDuration", Duration.ofSeconds(60));
        ReflectionTestUtils.setField(voteResultPhaseService, "voteDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(voteResultPhaseService, "voteSkippedDuration", Duration.ofSeconds(3));
        ReflectionTestUtils.setField(voteResultPhaseService, "resultDuration", Duration.ofSeconds(10));
        ReflectionTestUtils.setField(voteResultPhaseService, "guessDuration", Duration.ofSeconds(60));
        ReflectionTestUtils.setField(
                promptPhaseService, "imageGenerationCompletionDelay", Duration.ofSeconds(3));
    }

    @AfterEach
    void 게임_단계_전환_로그_appender를_제거한다() {
        gamePhaseLogger.detachAppender(gamePhaseLogAppender);
    }


    protected Map<String, Object> keyValues(ILoggingEvent logEvent) {
        return logEvent.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    protected List<String> setUpRoomInPlaying() {
        List<String> playerIds = setUpRoomWithImagesReady();
        captureScheduledPlayingTransition().run();
        return playerIds;
    }

    protected List<String> setUpRoomWithImagesReady() {
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
        gamePhaseService.submitPrompt("ABCD", created.playerId(), "호스트 프롬프트", PromptSubmissionType.NORMAL);
        runImageGenerationTask();
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "참가자1 프롬프트", PromptSubmissionType.NORMAL);
        runImageGenerationTask();
        gamePhaseService.submitPrompt("ABCD", guest2.playerId(), "참가자2 프롬프트", PromptSubmissionType.NORMAL);
        runImageGenerationTask();
        return List.of(created.playerId(), guest1.playerId(), guest2.playerId());
    }

    protected List<String> setUpRoomInVoting() {
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess(
                "ABCD", playerIds.get(1), "강아지가 기타를 치는 장면", GuessSubmissionType.NORMAL);
        gamePhaseService.submitGuess(
                "ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면", GuessSubmissionType.NORMAL);
        return playerIds;
    }

    protected List<String> setUpRoomInResults() {
        List<String> playerIds = setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);
        gamePhaseService.submitVote("ABCD", playerIds.get(2), answerOptionId);
        return playerIds;
    }

    protected String findAnswerOptionId(String code) {
        return gameRegistry.find(code)
                .orElseThrow()
                .getCurrentRound()
                .getAnswerEntry()
                .getPromptId();
    }

    protected String findOwnOptionId(String code, String playerId) {
        return gameRegistry.find(code)
                .orElseThrow()
                .getCurrentRoundOwnVoteOptions()
                .get(playerId)
                .optionId();
    }

    protected OwnVoteOptionNotice captureOwnVoteOption(String playerId) {
        ArgumentCaptor<OwnVoteOptionNotice> captor = ArgumentCaptor.forClass(OwnVoteOptionNotice.class);
        verify(messagingTemplate).convertAndSendToUser(eq(playerId), eq("/queue/vote-own-option"), captor.capture());
        return captor.getValue();
    }

    protected GuessSubmissionSnapshot captureGuessSubmission(String playerId) {
        ArgumentCaptor<GuessSubmissionSnapshot> captor = ArgumentCaptor.forClass(GuessSubmissionSnapshot.class);
        verify(messagingTemplate).convertAndSendToUser(eq(playerId), eq("/queue/guess-submission"), captor.capture());
        return captor.getValue();
    }

    protected RoundSnapshot captureRoundSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.ROUND_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (RoundSnapshot) message.payload();
    }

    protected VoteSnapshot captureVoteSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.VOTE_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (VoteSnapshot) message.payload();
    }

    protected VoteSkippedSnapshot captureVoteSkippedSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.VOTE_SKIPPED_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (VoteSkippedSnapshot) message.payload();
    }

    protected RoundResultSnapshot captureRoundResultSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.ROUND_RESULT_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (RoundResultSnapshot) message.payload();
    }

    protected GameResultSnapshot captureGameResultSnapshotBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.GAME_RESULT_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (GameResultSnapshot) message.payload();
    }

    protected Runnable captureLastScheduledDeadline(int expectedScheduleCount) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(gamePhaseDeadlineScheduler, times(expectedScheduleCount)).schedule(captor.capture(), any(Instant.class));
        return captor.getAllValues().get(expectedScheduleCount - 1);
    }

    protected GameSession startGeneratingGame() {
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

    protected void submitPromptAndCompleteImage(String playerId, String prompt) {
        gamePhaseService.submitPrompt("ABCD", playerId, prompt, PromptSubmissionType.NORMAL);
        runImageGenerationTask();
    }

    protected void givenGeneratedImage(String prompt, String imageUrl) {
        byte[] image = prompt.getBytes(StandardCharsets.UTF_8);
        given(imageGenerator.generate(new ImageGenerationRequest(prompt, "gemini-3.1-flash-image", "2K")))
                .willReturn(new GeneratedImage(image, "image/jpeg"));
        given(imageStorageClient.store(image, "image/jpeg")).willReturn(imageUrl);
    }

    protected void givenGenerationFailure(String prompt, RuntimeException exception) {
        given(imageGenerator.generate(new ImageGenerationRequest(prompt, "gemini-3.1-flash-image", "2K")))
                .willThrow(exception);
    }

    protected void givenGeneratedImages(String... imageUrls) {
        AtomicInteger storedImageIndex = new AtomicInteger();
        given(imageGenerator.generate(any(ImageGenerationRequest.class)))
                .willReturn(new GeneratedImage("image".getBytes(StandardCharsets.UTF_8), "image/jpeg"));
        given(imageStorageClient.store(any(byte[].class), eq("image/jpeg")))
                .willAnswer(invocation -> imageUrls[storedImageIndex.getAndIncrement()]);
    }

    protected void verifyGeneratedImage(String prompt) {
        verify(imageGenerator).generate(new ImageGenerationRequest(prompt, "gemini-3.1-flash-image", "2K"));
    }

    // host: READY(실제 이미지), 참가자1: GENERATING(제출했지만 이미지 미완료), 참가자2: WAITING(무제출)
    protected GameSession startExpirationScenarioWithMissingImages() {
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        gamePhaseService.submitPrompt("ABCD", session.guest1().playerId(), "참가자1 프롬프트", PromptSubmissionType.NORMAL);
        return session;
    }

    protected void runImageGenerationTask() {
        assertThat(imageGenerationTask).isNotNull();
        imageGenerationTask.run();
    }

    protected Runnable captureScheduledPromptExpiration() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(gamePhaseDeadlineScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    protected void expirePromptDeadline() {
        ReflectionTestUtils.setField(
                gameRegistry.find("ABCD").orElseThrow(), "promptDeadline", Instant.now().minusSeconds(1));
    }

    protected void expireGuessSubmissionDeadline() {
        ReflectionTestUtils.setField(
                gameRegistry.find("ABCD").orElseThrow(),
                "finalGuessSubmissionDeadline",
                Instant.now().minusSeconds(1));
    }

    protected void runGuessExpirationCallback() {
        guessPhaseService.runGuessExpiration(
                "ABCD",
                gameRegistry.find("ABCD").orElseThrow().getFinalGuessSubmissionDeadline(),
                voteResultPhaseService::completeGuessSubmission);
    }

    protected Runnable captureScheduledPlayingTransition() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(imageGenerationCompletionScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    protected LobbySnapshot captureLastLobbyBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.LOBBY_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (LobbySnapshot) message.payload();
    }

    protected List<RoomMessage> captureRoomBroadcasts(int expectedBroadcastCount) {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, times(expectedBroadcastCount))
                .convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        return captor.getAllValues();
    }

    protected LobbySnapshot captureLastBroadcast(int expectedBroadcastCount) {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, times(expectedBroadcastCount))
                .convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getValue();
        assertThat(message.type()).isEqualTo(RoomMessageType.LOBBY_SNAPSHOT);
        return (LobbySnapshot) message.payload();
    }

    protected PromptSubmissionSnapshot capturePromptSubmissionBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (PromptSubmissionSnapshot) message.payload();
    }

    protected PromptSubmissionSnapshot captureLastPromptSubmissionBroadcast() {
        ArgumentCaptor<RoomMessage> captor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/rooms/ABCD"), captor.capture());
        RoomMessage message = captor.getAllValues().stream()
                .filter(value -> value.type() == RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT)
                .reduce((previous, current) -> current)
                .orElseThrow();
        return (PromptSubmissionSnapshot) message.payload();
    }

    protected ImageGenerationEvent captureImageGenerationEvent(String playerId) {
        ArgumentCaptor<ImageGenerationEvent> captor = ArgumentCaptor.forClass(ImageGenerationEvent.class);
        verify(messagingTemplate, atLeastOnce())
                .convertAndSendToUser(eq(playerId), eq("/queue/image-generation"), captor.capture());
        return captor.getAllValues().getLast();
    }

    protected record GameSession(
            CreateGameResponse host,
            JoinGameResponse guest1,
            JoinGameResponse guest2
    ) {
    }

    protected PromptEntry findPromptEntry(String code, String playerId) {
        return gameRegistry.find(code)
                .orElseThrow()
                .getPromptEntries().stream()
                .filter(entry -> entry.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    protected List<String> autoPromptCandidates(String nickname) {
        return Arrays.stream(AutoPromptPrefix.values())
                .map(prefix -> prefix.value() + " " + nickname)
                .toList();
    }
}
