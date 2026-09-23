package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import com.igmo.monitoring.GameMetrics;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RedisGameRoomStateRepository;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
}
