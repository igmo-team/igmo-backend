package com.igmo.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisGameRoomStateRepositoryTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisGameRoomStateRepository repository;

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
        repository = new RedisGameRoomStateRepository(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules()
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
    @DisplayName("모든 게임 상태를 Redis JSON으로 저장하고 같은 상태로 복원한다.")
    void saveAndRestore_모든게임상태를보존한다() {
        // given
        GameRoom room = createResultsRoom();
        GameRoomState expected = GameRoomState.from(room);

        // when
        repository.save(room);
        Optional<GameRoomState> found = repository.find(room.getCode());
        Optional<GameRoom> restored = repository.restore(room.getCode());

        // then
        assertThat(redisTemplate.opsForValue().get("igmo:game-room:ABCD"))
                .contains("\"schemaVersion\":1")
                .contains("\"rounds\"");
        assertThat(found).contains(expected);
        assertThat(restored).isPresent();
        assertThat(GameRoomState.from(restored.orElseThrow())).isEqualTo(expected);
        assertThat(restored.orElseThrow().isSecretValid(
                room.getPlayers().getFirst().getId(),
                room.getPlayers().getFirst().getSecret()
        )).isTrue();
    }

    @ParameterizedTest
    @EnumSource(GamePhase.class)
    @DisplayName("각 게임 단계의 상태를 저장하고 같은 단계로 복원한다.")
    void saveAndRestore_각게임단계를보존한다(GamePhase phase) {
        // given
        GameRoom room = createRoomAtPhase(phase);
        GameRoomState expected = GameRoomState.from(room);

        // when
        repository.save(room);
        Optional<GameRoom> restored = repository.restore(room.getCode());

        // then
        assertThat(restored).isPresent();
        assertThat(restored.orElseThrow().getPhase()).isEqualTo(phase);
        assertThat(GameRoomState.from(restored.orElseThrow())).isEqualTo(expected);
    }

    @Test
    @DisplayName("존재하지 않는 방 조회 시 빈 결과를 반환한다.")
    void find_존재하지않는방은빈결과를반환한다() {
        // given
        String roomCode = "UNKNOWN";

        // when
        Optional<GameRoomState> state = repository.find(roomCode);

        // then
        assertThat(state).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 스키마 버전 조회 시 예외를 던진다.")
    void find_지원하지않는스키마버전이면예외를던진다() {
        // given
        redisTemplate.opsForValue().set("igmo:game-room:ABCD", "{\"schemaVersion\":99}");

        // when // then
        assertThatThrownBy(() -> repository.find("ABCD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 게임 방 상태를 읽을 수 없습니다.");
    }

    @Test
    @DisplayName("잘못된 JSON 조회 시 예외를 던진다.")
    void find_잘못된JSON이면예외를던진다() {
        // given
        redisTemplate.opsForValue().set("igmo:game-room:ABCD", "{invalid-json}");

        // when // then
        assertThatThrownBy(() -> repository.find("ABCD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 게임 방 상태를 읽을 수 없습니다.");
    }

    @Test
    @DisplayName("방 삭제 시 Redis 키를 삭제한다.")
    void delete_방삭제시Redis키를삭제한다() {
        // given
        repository.save(GameRoom.create("ABCD", new Player("호스트")));

        // when
        repository.delete("ABCD");

        // then
        assertThat(repository.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("중복 방 저장 시 기존 Redis 상태를 유지한다.")
    void saveIfAbsent_중복방은기존Redis상태를유지한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom firstRoom = GameRoom.create("ABCD", new Player("첫 번째"));
        GameRoom duplicateRoom = GameRoom.create("ABCD", new Player("두 번째"));
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(repository)
        );

        // when
        boolean firstSaved = gameRoomRepository.saveIfAbsent(firstRoom);
        boolean duplicateSaved = gameRoomRepository.saveIfAbsent(duplicateRoom);

        // then
        assertThat(firstSaved).isTrue();
        assertThat(duplicateSaved).isFalse();
        assertThat(repository.find("ABCD")).contains(GameRoomState.from(firstRoom));
        assertThat(repository.find("ABCD").orElseThrow().version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("서로 다른 인스턴스가 같은 방 코드를 저장하면 한쪽만 성공하고 기존 상태를 유지한다.")
    void saveIfAbsent_서로다른인스턴스에서동시에같은코드경쟁시한쪽만성공한다() throws Exception {
        // given
        GameRoom firstRoom = GameRoom.create("ABCD", new Player("첫 번째"));
        GameRoom secondRoom = GameRoom.create("ABCD", new Player("두 번째"));
        GameRegistry firstRegistry = new GameRegistry();
        GameRegistry secondRegistry = new GameRegistry();
        GameRoomRepository firstInstance = new GameRoomRepository(
                firstRegistry,
                Optional.of(repository)
        );
        GameRoomRepository secondInstance = new GameRoomRepository(
                secondRegistry,
                Optional.of(repository)
        );

        // when
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Boolean> firstResult = executor.submit(() -> {
            start.await();
            return firstInstance.saveIfAbsent(firstRoom);
        });
        Future<Boolean> secondResult = executor.submit(() -> {
            start.await();
            return secondInstance.saveIfAbsent(secondRoom);
        });
        boolean firstSaved;
        boolean secondSaved;
        try {
            firstSaved = firstResult.get(5, TimeUnit.SECONDS);
            secondSaved = secondResult.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(firstSaved).isNotEqualTo(secondSaved);
        if (firstSaved) {
            assertThat(firstRegistry.find("ABCD")).contains(firstRoom);
            assertThat(secondRegistry.find("ABCD")).isEmpty();
            assertThat(repository.find("ABCD")).contains(GameRoomState.from(firstRoom));
        } else {
            assertThat(firstRegistry.find("ABCD")).isEmpty();
            assertThat(secondRegistry.find("ABCD")).contains(secondRoom);
            assertThat(repository.find("ABCD")).contains(GameRoomState.from(secondRoom));
        }
    }

    @Test
    @DisplayName("Redis 상태가 있으면 새 로컬 저장소로 GameRoom을 복원한다.")
    void restore_새인스턴스에서Redis상태를GameRoom으로복원한다() {
        // given
        GameRoom original = createResultsRoom();
        GameRoomRepository writer = new GameRoomRepository(new GameRegistry(), Optional.of(repository));
        writer.saveIfAbsent(original);
        GameRoomRepository reader = new GameRoomRepository(new GameRegistry(), Optional.of(repository));

        // when
        Optional<GameRoom> restored = reader.restore(original.getCode());

        // then
        assertThat(restored).isPresent();
        assertThat(GameRoomState.from(restored.orElseThrow()))
                .isEqualTo(GameRoomState.from(original));
    }

    @Test
    @DisplayName("로컬에 방이 없으면 Redis 상태를 복원한 뒤 업데이트한다.")
    void update_로컬에방이없으면Redis상태를복원한뒤업데이트한다() {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"));
        GameRoomRepository writer = new GameRoomRepository(new GameRegistry(), Optional.of(repository));
        writer.saveIfAbsent(original);
        GameRegistry readerRegistry = new GameRegistry();
        GameRoomRepository reader = new GameRoomRepository(readerRegistry, Optional.of(repository));

        // when
        String playerId = reader.update("ABCD", room -> {
            Player player = new Player("참가자");
            room.addPlayer(player);
            return player.getId();
        });

        // then
        assertThat(playerId).isNotBlank();
        assertThat(readerRegistry.find("ABCD")).isPresent();
        assertThat(readerRegistry.find("ABCD").orElseThrow().getPlayers())
                .hasSize(2)
                .extracting(player -> player.getNickname().value())
                .containsExactly("호스트", "참가자");
        assertThat(repository.find("ABCD").orElseThrow().players())
                .hasSize(2)
                .extracting(GameRoomState.PlayerState::nickname)
                .containsExactly("호스트", "참가자");
    }

    @Test
    @DisplayName("방 제거 시 로컬 방과 Redis 상태를 함께 삭제한다.")
    void remove_방을제거하면로컬과Redis상태를함께삭제한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(repository)
        );
        gameRoomRepository.saveIfAbsent(room);

        // when
        gameRoomRepository.remove("ABCD");

        // then
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        assertThat(repository.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("상태 변경 중 방이 제거되면 Redis 상태도 삭제한다.")
    void update_상태변경중방이제거되면Redis상태를삭제한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(repository)
        );
        gameRoomRepository.saveIfAbsent(room);

        // when
        gameRoomRepository.update("ABCD", currentRoom -> {
            gameRegistry.remove(currentRoom.getCode());
            return null;
        });

        // then
        assertThat(repository.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("조건부 업데이트 후 Redis에 최신 상태를 저장한다.")
    void updateIfPresent_업데이트후Redis에최신상태를저장한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(repository)
        );
        gameRoomRepository.saveIfAbsent(room);
        AtomicBoolean operationInvoked = new AtomicBoolean(false);

        // when
        Optional<String> result = gameRoomRepository.updateIfPresent("ABCD", currentRoom -> {
            operationInvoked.set(true);
            currentRoom.changePlayerReady(currentRoom.getPlayers().getFirst().getId(), true);
            return "updated";
        });

        // then
        assertThat(result).contains("updated");
        assertThat(operationInvoked).isTrue();
        assertThat(repository.find("ABCD")).contains(GameRoomState.from(room));
        assertThat(repository.find("ABCD").orElseThrow().version()).isEqualTo(2L);
    }

    private GameRoom createResultsRoom() {
        GameRoom room = createVotingRoom();
        Round round = room.getCurrentRound();
        String answerId = round.getAnswerEntry().getPromptId();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        room.submitVote(room.getPlayers().get(1).getId(), answerId, base.plusSeconds(5));
        room.submitVote(room.getPlayers().get(2).getId(), answerId, base.plusSeconds(5));
        room.completeVoting(base.plusSeconds(6), Duration.ofSeconds(30));
        return room;
    }

    private GameRoom createRoomAtPhase(GamePhase phase) {
        return switch (phase) {
            case LOBBY -> createLobbyRoom();
            case GENERATING -> createGeneratingRoom();
            case PLAYING -> createPlayingRoom();
            case VOTING -> createVotingRoom();
            case VOTE_SKIPPED -> createVoteSkippedRoom();
            case RESULTS -> createResultsRoom();
            case ENDED -> createEndedRoom();
        };
    }

    private GameRoom createLobbyRoom() {
        Player host = new Player("호스트");
        Player second = new Player("두 번째");
        Player third = new Player("세 번째");
        GameRoom room = GameRoom.create("ABCD", host);
        room.addPlayer(second);
        room.addPlayer(third);
        return room;
    }

    private GameRoom createGeneratingRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = createLobbyRoom();
        Player host = room.getPlayers().getFirst();
        Player second = room.getPlayers().get(1);
        Player third = room.getPlayers().get(2);
        room.changePlayerReady(second.getId(), true);
        room.changePlayerReady(third.getId(), true);
        room.start(host.getId(), base, Duration.ofSeconds(30));
        return room;
    }

    private GameRoom createPlayingRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = createGeneratingRoom();
        for (Player player : room.getPlayers()) {
            room.submitPrompt(player.getId(), "프롬프트-" + player.getNickname().value(), base.plusSeconds(1));
            room.completeImageGeneration(player.getId(), "https://example.com/" + player.getId() + ".png");
        }
        room.advanceToPlaying();
        room.startRounds(base.plusSeconds(2), Duration.ofSeconds(30));
        return room;
    }

    private GameRoom createVotingRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = createPlayingRoom();
        Round round = room.getCurrentRound();
        for (Player player : room.getPlayers()) {
            if (!player.getId().equals(round.getQuestionerId())) {
                room.submitGuess(player.getId(), "추측-" + player.getNickname().value(), base.plusSeconds(3));
            }
        }
        room.completeGuessSubmission(base.plusSeconds(4), Duration.ofSeconds(30), Duration.ofSeconds(5));
        return room;
    }

    private GameRoom createVoteSkippedRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = createPlayingRoom();
        Round round = room.getCurrentRound();
        String answer = round.getAnswerEntry().getPrompt();
        for (Player player : room.getPlayers()) {
            if (!player.getId().equals(round.getQuestionerId())) {
                room.submitGuess(player.getId(), answer, base.plusSeconds(3));
            }
        }
        room.completeGuessSubmission(base.plusSeconds(40), Duration.ofSeconds(30), Duration.ofSeconds(5));
        return room;
    }

    private GameRoom createEndedRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = createResultsRoom();
        for (int roundIndex = 1; roundIndex < room.getTotalRoundCount(); roundIndex++) {
            Instant roundStartedAt = base.plusSeconds(10L * roundIndex);
            room.advanceRound(roundStartedAt, Duration.ofSeconds(30));
            Round round = room.getCurrentRound();
            for (Player player : room.getPlayers()) {
                if (!player.getId().equals(round.getQuestionerId())) {
                    room.submitGuess(player.getId(), "추측-라운드-" + roundIndex + "-" + player.getId(),
                            roundStartedAt.plusSeconds(1));
                }
            }
            room.completeGuessSubmission(roundStartedAt.plusSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(5));
            for (Player player : room.getPlayers()) {
                if (!player.getId().equals(round.getQuestionerId())) {
                    room.submitVote(
                            player.getId(),
                            round.getAnswerEntry().getPromptId(),
                            roundStartedAt.plusSeconds(3)
                    );
                }
            }
            room.completeVoting(roundStartedAt.plusSeconds(4), Duration.ofSeconds(30));
        }
        room.advanceRound(base.plusSeconds(40), Duration.ofSeconds(30));
        return room;
    }
}
