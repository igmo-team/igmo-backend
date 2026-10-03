package com.igmo.service.phase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import com.igmo.domain.Vote;
import com.igmo.monitoring.GameMetrics;
import com.igmo.service.GameDrainLifecycle;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.GameRoomStateSyncService;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import com.igmo.store.redis.GameRoomStateChangePublisher;
import com.igmo.store.redis.GameRoomStateChangeSubscriber;
import com.igmo.store.redis.RedisGameRoomStateRepository;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.message.RoomMessageType;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GamePhaseServiceRedisIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisGameRoomStateRepository redisRepository;

    @BeforeAll
    static void setUpRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                redis.getHost(),
                redis.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisRepository = new RedisGameRoomStateRepository(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                new GameMetrics(new SimpleMeterRegistry(), new GameRegistry(), "blue", "8080")
        );
    }

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterAll
    static void tearDownRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("마지막 라운드가 종료되면 로컬 게임방과 Redis 상태를 함께 제거한다.")
    void 마지막_라운드_종료시_로컬과Redis를_함께_제거한다() {
        // given
        TestContext context = createContext();
        GameRoom room = createResultsAtLastRound();
        context.repository().saveIfAbsent(room);
        Instant resultDeadline = room.getResultDeadline();

        // when
        ReflectionTestUtils.invokeMethod(
                context.voteResultPhaseService(),
                "runResultExpiration",
                room.getCode(),
                resultDeadline
        );

        // then
        ArgumentCaptor<RoomMessage<?>> messageCaptor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(context.eventPublisher()).publish(eq(room.getCode()), messageCaptor.capture());
        assertThat(messageCaptor.getValue().type()).isEqualTo(RoomMessageType.GAME_RESULT_SNAPSHOT);
        assertThat(context.registry().find(room.getCode())).isEmpty();
        assertThat(redisRepository.find(room.getCode())).isEmpty();
        verify(context.gameDrainLifecycle()).onGameEnded(room.getCode());
    }

    @Test
    @DisplayName("중간 라운드 결과가 종료되면 다음 라운드와 Redis 상태를 유지한다.")
    void 중간_라운드_결과에서는_게임방을_제거하지_않는다() {
        // given
        TestContext context = createContext();
        GameRoom room = createResultsRoom();
        context.repository().saveIfAbsent(room);
        Instant resultDeadline = room.getResultDeadline();

        // when
        ReflectionTestUtils.invokeMethod(
                context.voteResultPhaseService(),
                "runResultExpiration",
                room.getCode(),
                resultDeadline
        );

        // then
        assertThat(context.registry().find(room.getCode())).isPresent();
        assertThat(context.registry().find(room.getCode()).orElseThrow().getPhase())
                .isEqualTo(GamePhase.PLAYING);
        assertThat(redisRepository.find(room.getCode())).get()
                .extracting(state -> state.phase())
                .isEqualTo(GamePhase.PLAYING);
        verifyNoInteractions(context.gameDrainLifecycle());
    }

    @Test
    @DisplayName("오래된 결과 마감 작업은 Redis 상태와 게임방을 제거하지 않는다.")
    void 오래된_결과_마감_작업은_게임방을_제거하지_않는다() {
        // given
        TestContext context = createContext();
        GameRoom room = createResultsAtLastRound();
        context.repository().saveIfAbsent(room);
        Instant staleDeadline = room.getResultDeadline().plusSeconds(1);

        // when
        ReflectionTestUtils.invokeMethod(
                context.voteResultPhaseService(),
                "runResultExpiration",
                room.getCode(),
                staleDeadline
        );

        // then
        assertThat(context.registry().find(room.getCode())).isPresent();
        assertThat(redisRepository.find(room.getCode())).get()
                .extracting(state -> state.phase())
                .isEqualTo(GamePhase.RESULTS);
        verifyNoInteractions(context.eventPublisher(), context.gameDrainLifecycle());
    }

    @Test
    @DisplayName("오래된 로컬 상태에서 투표해도 두 인스턴스의 서로 다른 투표를 모두 보존한다.")
    void 서로_다른_인스턴스의_투표를_CAS_재시도로_모두_보존한다() {
        GameRoomStateChangePublisher publisherA = new GameRoomStateChangePublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        GameRoomStateChangePublisher publisherB = new GameRoomStateChangePublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        TestContext contextA = createClusterContext(
                "instance-a", mock(SimpMessagingTemplate.class), publisherA);
        TestContext contextB = createClusterContext(
                "instance-b", mock(SimpMessagingTemplate.class), publisherB);
        GameRoom room = createVotingRoom();
        ReflectionTestUtils.setField(room, "voteDeadline", Instant.now().plusSeconds(60));
        contextA.repository().saveIfAbsent(room);
        contextB.repository().restore(room.getCode()).orElseThrow();

        List<String> voterIds = room.getPlayers().stream()
                .map(Player::getId)
                .filter(playerId -> !playerId.equals(room.getCurrentRound().getQuestionerId()))
                .toList();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();

        contextA.voteResultPhaseService().submitVote(room.getCode(), voterIds.get(0), answerOptionId);
        contextB.voteResultPhaseService().submitVote(room.getCode(), voterIds.get(1), answerOptionId);

        List<Vote> savedVotes = redisRepository.restore(room.getCode()).orElseThrow()
                .getCurrentRound().getVotes();
        assertThat(savedVotes)
                .extracting(Vote::getVoterId)
                .containsExactlyInAnyOrderElementsOf(voterIds);
        assertThat(redisRepository.restore(room.getCode()).orElseThrow().getPhase())
                .isEqualTo(GamePhase.RESULTS);
    }

    @Test
    @DisplayName("두 인스턴스의 결과 마감이 겹쳐도 한 번만 전환하고 최신 상태를 보존한다.")
    void 두_인스턴스의_결과_마감이_겹쳐도_한번만_전환하고_최신상태를_보존한다() throws Exception {
        // given
        GameRoomStateChangePublisher publisherA = new GameRoomStateChangePublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        GameRoomStateChangePublisher publisherB = new GameRoomStateChangePublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        SimpMessagingTemplate brokerA = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate brokerB = mock(SimpMessagingTemplate.class);
        TestContext contextA = createClusterContext("instance-a", brokerA, publisherA);
        TestContext contextB = createClusterContext("instance-b", brokerB, publisherB);
        GameRoom room = createResultsRoom();
        contextA.repository().saveIfAbsent(room);
        Instant resultDeadline = room.getResultDeadline();
        GameRoom staleRoomOnB = contextB.repository().restore(room.getCode()).orElseThrow();
        assertThat(staleRoomOnB).isNotSameAs(room);

        CountDownLatch instanceBReceivedAChange = new CountDownLatch(1);
        CountDownLatch allowInstanceBToSync = new CountDownLatch(1);
        RedisMessageListenerContainer listenerA = startListener(subscriber(contextA, publisherA));
        RedisMessageListenerContainer listenerB = startListener(gatedSubscriber(
                contextB, publisherB, instanceBReceivedAChange, allowInstanceBToSync));

        try {
            // when: A 전환 후 B가 상태 동기화하기 전에 B의 오래된 타이머도 실행한다.
            contextA.voteResultPhaseService().runResultExpiration(room.getCode(), resultDeadline);
            assertThat(instanceBReceivedAChange.await(5, TimeUnit.SECONDS)).isTrue();

            GameRoom roomOnA = contextA.registry().find(room.getCode()).orElseThrow();
            String guessingPlayerId = roomOnA.getPlayers().stream()
                    .map(Player::getId)
                    .filter(playerId -> !playerId.equals(roomOnA.getCurrentRound().getQuestionerId()))
                    .findFirst()
                    .orElseThrow();
            contextA.repository().updateWithCas(room.getCode(), currentRoom -> {
                currentRoom.submitGuess(guessingPlayerId, "A에서 수락된 추측", Instant.now());
                return RoomUpdate.changed(null, List.of());
            });
            GameRoom finalRoom = redisRepository.restore(room.getCode()).orElseThrow();
            assertThat(finalRoom.getCurrentRound().hasGuess(guessingPlayerId)).isTrue();
            assertThat(finalRoom.getCurrentRound().getRoundNumber()).isEqualTo(2);

            contextB.voteResultPhaseService().runResultExpiration(room.getCode(), resultDeadline);
            assertThat(redisRepository.restore(room.getCode()).orElseThrow()
                    .getCurrentRound().hasGuess(guessingPlayerId)).isTrue();
            allowInstanceBToSync.countDown();
            await(() -> invocationCount(brokerA) == 1 && invocationCount(brokerB) == 1);
            assertRoundSnapshots(brokerA, 1);
            assertRoundSnapshots(brokerB, 1);
        } finally {
            allowInstanceBToSync.countDown();
            listenerA.stop();
            listenerB.stop();
        }
    }

    @Test
    @DisplayName("같은 마감 시각의 두 인스턴스 타이머도 한 번만 전환을 방송한다.")
    void 같은_마감시각의_두_인스턴스_타이머도_한번만_전환을_방송한다() throws Exception {
        ThreadPoolTaskScheduler schedulerA = createScheduler("instance-a-");
        ThreadPoolTaskScheduler schedulerB = createScheduler("instance-b-");
        schedulerA.initialize();
        schedulerB.initialize();

        GameRoomStateChangePublisher publisherA = new GameRoomStateChangePublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        GameRoomStateChangePublisher publisherB = new GameRoomStateChangePublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules());
        SimpMessagingTemplate brokerA = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate brokerB = mock(SimpMessagingTemplate.class);
        TestContext contextA = createClusterContext(
                "instance-a", brokerA, publisherA, new GamePhaseScheduler(schedulerA, schedulerA));
        TestContext contextB = createClusterContext(
                "instance-b", brokerB, publisherB, new GamePhaseScheduler(schedulerB, schedulerB));
        GameRoom room = createResultsRoom();
        Instant sameDeadline = Instant.now().plusSeconds(1);
        ReflectionTestUtils.setField(room, "resultDeadline", sameDeadline);
        contextA.repository().saveIfAbsent(room);
        contextB.repository().restore(room.getCode()).orElseThrow();

        CountDownLatch instanceAReceivedChange = new CountDownLatch(1);
        CountDownLatch instanceBReceivedChange = new CountDownLatch(1);
        CountDownLatch allowInstanceAToSync = new CountDownLatch(1);
        CountDownLatch allowInstanceBToSync = new CountDownLatch(1);
        RedisMessageListenerContainer listenerA = startListener(gatedSubscriber(
                contextA, publisherA, instanceAReceivedChange, allowInstanceAToSync));
        RedisMessageListenerContainer listenerB = startListener(gatedSubscriber(
                contextB, publisherB, instanceBReceivedChange, allowInstanceBToSync));

        try {
            contextA.voteResultPhaseService().scheduleResultExpiration(room.getCode(), sameDeadline);
            contextB.voteResultPhaseService().scheduleResultExpiration(room.getCode(), sameDeadline);

            assertThat(instanceAReceivedChange.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(instanceBReceivedChange.await(5, TimeUnit.SECONDS)).isTrue();
            await(() -> invocationCount(brokerA) + invocationCount(brokerB) == 1);

            allowInstanceAToSync.countDown();
            allowInstanceBToSync.countDown();
            await(() -> invocationCount(brokerA) == 1 && invocationCount(brokerB) == 1);
            assertRoundSnapshots(brokerA, 1);
            assertRoundSnapshots(brokerB, 1);
            assertThat(redisRepository.restore(room.getCode()).orElseThrow()
                    .getCurrentRound().getRoundNumber()).isEqualTo(2);
        } finally {
            allowInstanceAToSync.countDown();
            allowInstanceBToSync.countDown();
            listenerA.stop();
            listenerB.stop();
            schedulerA.shutdown();
            schedulerB.shutdown();
        }
    }

    private TestContext createContext() {
        GameRegistry registry = new GameRegistry();
        GameRoomRepository repository = new GameRoomRepository(registry, Optional.of(redisRepository));
        GameEventPublisher eventPublisher = mock(GameEventPublisher.class);
        GameDrainLifecycle gameDrainLifecycle = mock(GameDrainLifecycle.class);
        GamePhaseScheduler gamePhaseScheduler = mock(GamePhaseScheduler.class);
        GuessPhaseService guessPhaseService = new GuessPhaseService(
                repository,
                gamePhaseScheduler,
                eventPublisher);
        VoteResultPhaseService voteResultPhaseService = new VoteResultPhaseService(
                repository,
                gamePhaseScheduler,
                eventPublisher,
                gameDrainLifecycle,
                guessPhaseService);
        ReflectionTestUtils.setField(voteResultPhaseService, "voteDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(voteResultPhaseService, "voteSkippedDuration", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(voteResultPhaseService, "resultDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(
                voteResultPhaseService, "guessDuration", Duration.ofSeconds(30));
        return new TestContext(registry, repository, eventPublisher, gameDrainLifecycle, voteResultPhaseService);
    }

    private TestContext createClusterContext(
            String instanceId,
            SimpMessagingTemplate broker,
            GameRoomStateChangePublisher stateChangePublisher
    ) {
        return createClusterContext(
                instanceId, broker, stateChangePublisher, mock(GamePhaseScheduler.class));
    }

    private TestContext createClusterContext(
            String instanceId,
            SimpMessagingTemplate broker,
            GameRoomStateChangePublisher stateChangePublisher,
            GamePhaseScheduler gamePhaseScheduler
    ) {
        GameRegistry registry = new GameRegistry();
        GameRoomRepository repository = new GameRoomRepository(
                registry,
                Optional.of(redisRepository),
                Optional.of(stateChangePublisher),
                mock(ApplicationEventPublisher.class));
        GameEventPublisher eventPublisher = new GameEventPublisher(
                broker,
                new GameMetrics(new SimpleMeterRegistry(), registry, instanceId, "8080"));
        GameDrainLifecycle gameDrainLifecycle = mock(GameDrainLifecycle.class);
        GuessPhaseService guessPhaseService = new GuessPhaseService(
                repository, gamePhaseScheduler, eventPublisher);
        VoteResultPhaseService voteResultPhaseService = new VoteResultPhaseService(
                repository, gamePhaseScheduler, eventPublisher, gameDrainLifecycle, guessPhaseService);
        ReflectionTestUtils.setField(voteResultPhaseService, "voteDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(voteResultPhaseService, "voteSkippedDuration", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(voteResultPhaseService, "resultDuration", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(voteResultPhaseService, "guessDuration", Duration.ofSeconds(30));
        return new TestContext(registry, repository, eventPublisher, gameDrainLifecycle, voteResultPhaseService);
    }

    private ThreadPoolTaskScheduler createScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        return scheduler;
    }

    private GameRoomStateChangeSubscriber subscriber(
            TestContext context,
            GameRoomStateChangePublisher publisher
    ) {
        return new GameRoomStateChangeSubscriber(
                new ObjectMapper().findAndRegisterModules(),
                publisher,
                context.repository(),
                new GameRoomStateSyncService(context.repository(), context.eventPublisher()),
                context.eventPublisher());
    }

    private GameRoomStateChangeSubscriber gatedSubscriber(
            TestContext context,
            GameRoomStateChangePublisher publisher,
            CountDownLatch messageReceived,
            CountDownLatch allowSync
    ) {
        return new GameRoomStateChangeSubscriber(
                new ObjectMapper().findAndRegisterModules(),
                publisher,
                context.repository(),
                new GameRoomStateSyncService(context.repository(), context.eventPublisher()),
                context.eventPublisher()) {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                messageReceived.countDown();
                try {
                    if (!allowSync.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                super.onMessage(message, pattern);
            }
        };
    }

    private RedisMessageListenerContainer startListener(GameRoomStateChangeSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(GameRoomStateChangePublisher.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        await(container::isListening);
        return container;
    }

    private void assertRoundSnapshots(SimpMessagingTemplate broker, int expectedCount) {
        ArgumentCaptor<RoomMessage> messageCaptor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(broker, times(expectedCount)).convertAndSend(eq("/topic/rooms/ABCD"), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .extracting(RoomMessage::type)
                .containsOnly(RoomMessageType.ROUND_SNAPSHOT);
    }

    private int invocationCount(SimpMessagingTemplate broker) {
        return (int) org.mockito.Mockito.mockingDetails(broker).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("convertAndSend"))
                .count();
    }

    private static void await(Check check) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.matches()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Redis Pub/Sub 처리 결과가 시간 내 도착하지 않았습니다.");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Redis Pub/Sub 대기 중 인터럽트되었습니다.", exception);
            }
        }
    }

    private GameRoom createResultsAtLastRound() {
        GameRoom room = createResultsRoom();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int roundIndex = 1; roundIndex < room.getTotalRoundCount(); roundIndex++) {
            Instant roundStartedAt = base.plusSeconds(10L * roundIndex);
            room.advanceRound(roundStartedAt, Duration.ofSeconds(30));
            completeRoundToResults(room, roundStartedAt);
        }
        return room;
    }

    private GameRoom createResultsRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = createVotingRoom();
        Round round = room.getCurrentRound();
        String answerId = round.getAnswerEntry().getPromptId();
        for (Player player : room.getPlayers()) {
            if (!player.getId().equals(round.getQuestionerId())) {
                room.submitVote(player.getId(), answerId, base.plusSeconds(5));
            }
        }
        room.completeVoting(base.plusSeconds(6), Duration.ofSeconds(30));
        return room;
    }

    private void completeRoundToResults(GameRoom room, Instant roundStartedAt) {
        Round round = room.getCurrentRound();
        for (Player player : room.getPlayers()) {
            if (!player.getId().equals(round.getQuestionerId())) {
                room.submitGuess(
                        player.getId(),
                        "추측-라운드-" + round.getRoundNumber() + "-" + player.getId(),
                        roundStartedAt.plusSeconds(1)
                );
            }
        }
        room.completeGuessSubmission(roundStartedAt.plusSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(5));
        round = room.getCurrentRound();
        String answerId = round.getAnswerEntry().getPromptId();
        for (Player player : room.getPlayers()) {
            if (!player.getId().equals(round.getQuestionerId())) {
                room.submitVote(player.getId(), answerId, roundStartedAt.plusSeconds(3));
            }
        }
        room.completeVoting(roundStartedAt.plusSeconds(4), Duration.ofSeconds(30));
    }

    private GameRoom createVotingRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(room.getHostId(), base, Duration.ofSeconds(30));
        for (Player player : room.getPlayers()) {
            room.submitPrompt(player.getId(), "프롬프트-" + player.getNickname().value(), base.plusSeconds(1));
            room.completeImageGeneration(player.getId(), "https://example.com/" + player.getId() + ".png");
        }
        room.advanceToPlaying();
        room.startRounds(base.plusSeconds(2), Duration.ofSeconds(30));
        Round round = room.getCurrentRound();
        for (Player player : room.getPlayers()) {
            if (!player.getId().equals(round.getQuestionerId())) {
                room.submitGuess(player.getId(), "추측-" + player.getId(), base.plusSeconds(3));
            }
        }
        room.completeGuessSubmission(base.plusSeconds(4), Duration.ofSeconds(30), Duration.ofSeconds(5));
        return room;
    }

    private record TestContext(
            GameRegistry registry,
            GameRoomRepository repository,
            GameEventPublisher eventPublisher,
            GameDrainLifecycle gameDrainLifecycle,
            VoteResultPhaseService voteResultPhaseService
    ) {
    }

    @FunctionalInterface
    private interface Check {

        boolean matches();
    }
}
