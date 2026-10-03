package com.igmo.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.domain.Player;
import com.igmo.domain.Round;
import com.igmo.monitoring.GameMetrics;
import com.igmo.store.ConcurrentGameRoomUpdateException;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import com.igmo.store.redis.GameRoomStateChangePublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
            DockerImageName.parse("redis:7.4-alpine")
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
    @DisplayName("모든 게임 상태를 Redis JSON으로 저장하고 같은 상태로 복원한다.")
    void saveAndRestore_모든게임상태를보존한다() {
        // given
        GameRoom room = createResultsRoom();
        GameRoomState expected = GameRoomState.from(room);

        // when
        repository.saveIfAbsent(room);
        Optional<GameRoomState> found = repository.find(room.getCode());
        Optional<GameRoom> restored = repository.restore(room.getCode());

        // then
        assertThat(redisTemplate.opsForValue().get("igmo:game-room:ABCD"))
                .contains("\"schemaVersion\":3")
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
        repository.saveIfAbsent(room);
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
        repository.saveIfAbsent(GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10)));

        // when
        boolean deleted = repository.delete("ABCD");

        // then
        assertThat(deleted).isTrue();
        assertThat(repository.find("ABCD")).isEmpty();
        assertThat(repository.delete("ABCD")).isFalse();
    }

    @Test
    @DisplayName("방 생성 시 Redis 키에 1시간 TTL을 설정한다.")
    void saveIfAbsent_방생성시한시간TTL을설정한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));

        // when
        boolean saved = repository.saveIfAbsent(room);

        // then
        assertThat(saved).isTrue();
        assertThat(redisTemplate.getExpire("igmo:game-room:ABCD", TimeUnit.SECONDS))
                .isGreaterThan(3_500L)
                .isLessThanOrEqualTo(3_600L);
    }

    @Test
    @DisplayName("상태 변경 시 기존 Redis TTL을 갱신하지 않고 유지한다.")
    void save_상태변경시기존TTL을유지한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(room);
        redisTemplate.expire("igmo:game-room:ABCD", 30, TimeUnit.SECONDS);
        long ttlBeforeSave = redisTemplate.getExpire("igmo:game-room:ABCD", TimeUnit.SECONDS);

        // when
        room.changePlayerReady(room.getPlayers().getFirst().getId(), true);
        repository.save(room);

        // then
        long ttlAfterSave = redisTemplate.getExpire("igmo:game-room:ABCD", TimeUnit.SECONDS);
        assertThat(ttlBeforeSave).isGreaterThan(0L);
        assertThat(ttlAfterSave).isGreaterThan(0L).isLessThanOrEqualTo(ttlBeforeSave);
    }

    @Test
    @DisplayName("존재하지 않는 Redis 키는 상태 변경으로 다시 생성하지 않는다.")
    void save_존재하지않는키를상태변경으로다시생성하지않는다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));

        // when // then
        assertThatThrownBy(() -> repository.save(room))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 게임 방 상태를 갱신할 수 없습니다.");
        assertThat(repository.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("기준 버전이 일치하면 새 상태를 저장하고 기존 TTL을 유지한다.")
    void compareAndSet_기준버전이일치하면저장하고TTL을유지한다() {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(original);
        GameRoom candidate = GameRoom.restore(GameRoomState.from(original));
        candidate.addPlayer(new Player("참가자"));
        candidate.incrementVersion();
        redisTemplate.expire("igmo:game-room:ABCD", 30, TimeUnit.SECONDS);
        long ttlBeforeSave = redisTemplate.getExpire("igmo:game-room:ABCD", TimeUnit.SECONDS);

        // when
        RedisGameRoomStateRepository.ConditionalWriteResult result =
                repository.compareAndSet(candidate, original.getVersion());

        // then
        GameRoomState saved = repository.find("ABCD").orElseThrow();
        long ttlAfterSave = redisTemplate.getExpire("igmo:game-room:ABCD", TimeUnit.SECONDS);
        assertThat(result).isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED);
        assertThat(saved.version()).isEqualTo(original.getVersion() + 1);
        assertThat(saved.players()).hasSize(2);
        assertThat(ttlAfterSave).isGreaterThan(0L).isLessThanOrEqualTo(ttlBeforeSave);
    }

    @Test
    @DisplayName("기준 버전이 오래되면 Redis의 최신 상태를 덮어쓰지 않는다.")
    void compareAndSet_기준버전이오래되면최신상태를보존한다() {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(original);
        GameRoom winner = GameRoom.restore(GameRoomState.from(original));
        winner.addPlayer(new Player("먼저 저장한 참가자"));
        winner.incrementVersion();
        repository.compareAndSet(winner, original.getVersion());

        GameRoom staleCandidate = GameRoom.restore(GameRoomState.from(original));
        staleCandidate.changePlayerReady(staleCandidate.getHostId(), true);
        staleCandidate.incrementVersion();

        // when
        RedisGameRoomStateRepository.ConditionalWriteResult result =
                repository.compareAndSet(staleCandidate, original.getVersion());

        // then
        assertThat(result).isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.VERSION_MISMATCH);
        assertThat(repository.find("ABCD")).contains(GameRoomState.from(winner));
    }

    @Test
    @DisplayName("기준 버전이 일치하는 한 인스턴스만 동시에 저장한다.")
    void compareAndSet_동시에저장하면한인스턴스만성공한다() throws Exception {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(original);
        GameRoom firstCandidate = GameRoom.restore(GameRoomState.from(original));
        firstCandidate.addPlayer(new Player("첫 번째 참가자"));
        firstCandidate.incrementVersion();
        GameRoom secondCandidate = GameRoom.restore(GameRoomState.from(original));
        secondCandidate.addPlayer(new Player("두 번째 참가자"));
        secondCandidate.incrementVersion();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        Future<RedisGameRoomStateRepository.ConditionalWriteResult> firstResult = executor.submit(() -> {
            start.await();
            return repository.compareAndSet(firstCandidate, original.getVersion());
        });
        Future<RedisGameRoomStateRepository.ConditionalWriteResult> secondResult = executor.submit(() -> {
            start.await();
            return repository.compareAndSet(secondCandidate, original.getVersion());
        });
        RedisGameRoomStateRepository.ConditionalWriteResult first;
        RedisGameRoomStateRepository.ConditionalWriteResult second;
        try {
            first = firstResult.get(5, TimeUnit.SECONDS);
            second = secondResult.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(java.util.List.of(first, second)).containsExactlyInAnyOrder(
                RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED,
                RedisGameRoomStateRepository.ConditionalWriteResult.VERSION_MISMATCH
        );
        assertThat(repository.find("ABCD").orElseThrow().version()).isEqualTo(original.getVersion() + 1);
    }

    @Test
    @DisplayName("동시 변경 충돌 시 최신 상태에 원래 명령을 재적용하고 성공한 변경만 발행한다.")
    void updateWithCas_동시충돌시최신상태에명령을재적용한다() throws Exception {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(original);
        GameRoomStateChangePublisher firstPublisher = mock(GameRoomStateChangePublisher.class);
        GameRoomStateChangePublisher secondPublisher = mock(GameRoomStateChangePublisher.class);
        GameRegistry firstRegistry = new GameRegistry();
        GameRegistry secondRegistry = new GameRegistry();
        firstRegistry.saveIfAbsent(repository.restore("ABCD").orElseThrow());
        secondRegistry.saveIfAbsent(repository.restore("ABCD").orElseThrow());
        GameRoomRepository firstInstance = createCasGameRoomRepository(firstRegistry, firstPublisher);
        GameRoomRepository secondInstance = createCasGameRoomRepository(secondRegistry, secondPublisher);
        Player firstPlayer = new Player("인스턴스 A 사용자");
        Player secondPlayer = new Player("인스턴스 B 사용자");
        AtomicInteger firstAttempts = new AtomicInteger();
        AtomicInteger secondAttempts = new AtomicInteger();
        CyclicBarrier firstAttemptBarrier = new CyclicBarrier(2);
        List<GameRoomDelivery> deliveries = List.of(GameRoomDelivery.roomSnapshot());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        Future<RoomUpdate<String>> firstResult = executor.submit(() -> firstInstance.updateWithCas("ABCD", room -> {
            if (firstAttempts.incrementAndGet() == 1) {
                await(firstAttemptBarrier);
            }
            room.addPlayer(firstPlayer);
            return RoomUpdate.changed(firstPlayer.getId(), deliveries);
        }));
        Future<RoomUpdate<String>> secondResult = executor.submit(() -> secondInstance.updateWithCas("ABCD", room -> {
            if (secondAttempts.incrementAndGet() == 1) {
                await(firstAttemptBarrier);
            }
            room.addPlayer(secondPlayer);
            return RoomUpdate.changed(secondPlayer.getId(), deliveries);
        }));
        RoomUpdate<String> firstUpdate;
        RoomUpdate<String> secondUpdate;
        try {
            firstUpdate = firstResult.get(5, TimeUnit.SECONDS);
            secondUpdate = secondResult.get(5, TimeUnit.SECONDS);
            assertThat(List.of(firstUpdate.value(), secondUpdate.value()))
                    .containsExactly(firstPlayer.getId(), secondPlayer.getId());
        } finally {
            executor.shutdownNow();
        }

        // then
        GameRoomState saved = repository.find("ABCD").orElseThrow();
        assertThat(saved.players()).extracting(GameRoomState.PlayerState::nickname)
                .contains("인스턴스 A 사용자", "인스턴스 B 사용자");
        assertThat(saved.version()).isEqualTo(2L);
        assertThat(List.of(firstUpdate.changed(), secondUpdate.changed())).containsOnly(true);
        assertThat(List.of(firstAttempts.get(), secondAttempts.get())).containsExactlyInAnyOrder(1, 2);
        verify(firstPublisher).publish(eq("ABCD"), anyLong(), eq(deliveries));
        verify(secondPublisher).publish(eq("ABCD"), anyLong(), eq(deliveries));
    }

    @Test
    @DisplayName("같은 전환 명령이 경합하면 한 번만 변경하고 한 번만 발행한다.")
    void updateWithCas_같은전환명령은한번만발행한다() throws Exception {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(original);
        GameRoomStateChangePublisher firstPublisher = mock(GameRoomStateChangePublisher.class);
        GameRoomStateChangePublisher secondPublisher = mock(GameRoomStateChangePublisher.class);
        GameRegistry firstRegistry = new GameRegistry();
        GameRegistry secondRegistry = new GameRegistry();
        firstRegistry.saveIfAbsent(repository.restore("ABCD").orElseThrow());
        secondRegistry.saveIfAbsent(repository.restore("ABCD").orElseThrow());
        GameRoomRepository firstInstance = createCasGameRoomRepository(firstRegistry, firstPublisher);
        GameRoomRepository secondInstance = createCasGameRoomRepository(secondRegistry, secondPublisher);
        AtomicInteger firstAttempts = new AtomicInteger();
        AtomicInteger secondAttempts = new AtomicInteger();
        CyclicBarrier firstAttemptBarrier = new CyclicBarrier(2);
        List<GameRoomDelivery> deliveries = List.of(GameRoomDelivery.roomSnapshot());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        Future<RoomUpdate<String>> firstResult = executor.submit(() -> firstInstance.updateWithCas("ABCD", room -> {
            if (firstAttempts.incrementAndGet() == 1) {
                await(firstAttemptBarrier);
            }
            Player host = room.getPlayers().getFirst();
            if (host.isReady()) {
                return RoomUpdate.unchanged("already-ready");
            }
            room.changePlayerReady(host.getId(), true);
            return RoomUpdate.changed("ready", deliveries);
        }));
        Future<RoomUpdate<String>> secondResult = executor.submit(() -> secondInstance.updateWithCas("ABCD", room -> {
            if (secondAttempts.incrementAndGet() == 1) {
                await(firstAttemptBarrier);
            }
            Player host = room.getPlayers().getFirst();
            if (host.isReady()) {
                return RoomUpdate.unchanged("already-ready");
            }
            room.changePlayerReady(host.getId(), true);
            return RoomUpdate.changed("ready", deliveries);
        }));
        RoomUpdate<String> firstUpdate;
        RoomUpdate<String> secondUpdate;
        try {
            firstUpdate = firstResult.get(5, TimeUnit.SECONDS);
            secondUpdate = secondResult.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        GameRoomState saved = repository.find("ABCD").orElseThrow();
        assertThat(saved.players().getFirst().ready()).isTrue();
        assertThat(saved.version()).isEqualTo(1L);
        assertThat(List.of(firstUpdate.changed(), secondUpdate.changed())).containsExactlyInAnyOrder(true, false);
        assertThat(List.of(firstAttempts.get(), secondAttempts.get())).containsExactlyInAnyOrder(1, 2);
        if (firstUpdate.changed()) {
            verify(firstPublisher).publish(eq("ABCD"), anyLong(), eq(deliveries));
            verify(secondPublisher, never()).publish(anyString(), anyLong(), anyList());
        } else {
            verify(firstPublisher, never()).publish(anyString(), anyLong(), anyList());
            verify(secondPublisher).publish(eq("ABCD"), anyLong(), eq(deliveries));
        }
    }

    @Test
    @DisplayName("CAS 충돌이 세 번 이어지면 저장하지 않고 명령을 실패 처리한다.")
    void updateWithCas_세번충돌하면저장없이실패한다() {
        // given
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(original);
        GameRoomStateChangePublisher publisher = mock(GameRoomStateChangePublisher.class);
        GameRegistry registry = new GameRegistry();
        GameRoomRepository instance = createCasGameRoomRepository(registry, publisher);
        Player requestedPlayer = new Player("요청 참가자");
        AtomicInteger attempts = new AtomicInteger();

        // when // then
        assertThatThrownBy(() -> instance.updateWithCas("ABCD", room -> {
            int attempt = attempts.incrementAndGet();
            room.addPlayer(requestedPlayer);

            GameRoom competingRoom = repository.restore("ABCD").orElseThrow();
            competingRoom.addPlayer(new Player("경쟁 변경 " + attempt));
            long expectedVersion = competingRoom.getVersion();
            competingRoom.incrementVersion();
            assertThat(repository.compareAndSet(competingRoom, expectedVersion))
                    .isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED);

            return RoomUpdate.changed(requestedPlayer.getId(), List.of(GameRoomDelivery.roomSnapshot()));
        }))
                .isInstanceOf(ConcurrentGameRoomUpdateException.class)
                .hasMessage("게임방 상태가 동시에 변경되어 저장하지 못했습니다. roomCode=ABCD, attempts=3");

        GameRoomState saved = repository.find("ABCD").orElseThrow();
        assertThat(attempts).hasValue(3);
        assertThat(saved.players()).extracting(GameRoomState.PlayerState::nickname)
                .contains("경쟁 변경 1", "경쟁 변경 2", "경쟁 변경 3")
                .doesNotContain("요청 참가자");
        assertThat(saved.version()).isEqualTo(3L);
        assertThat(registry.find("ABCD").orElseThrow().getVersion()).isEqualTo(3L);
        verify(publisher, never()).publish(anyString(), anyLong(), anyList());
    }

    @Test
    @DisplayName("조건부 저장은 Redis 키가 없으면 새 키를 만들지 않는다.")
    void compareAndSet_키가없으면상태를생성하지않는다() {
        // given
        GameRoom candidate = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        candidate.incrementVersion();

        // when
        RedisGameRoomStateRepository.ConditionalWriteResult result =
                repository.compareAndSet(candidate, 0L);

        // then
        assertThat(result).isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND);
        assertThat(repository.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("조건부 삭제는 기준 버전이 일치할 때만 방을 삭제한다.")
    void compareAndDelete_기준버전이일치할때만삭제한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        repository.saveIfAbsent(room);

        // when // then
        assertThat(repository.compareAndDelete("ABCD", room.getVersion() + 1))
                .isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.VERSION_MISMATCH);
        assertThat(repository.find("ABCD")).contains(GameRoomState.from(room));
        assertThat(repository.compareAndDelete("ABCD", room.getVersion()))
                .isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED);
        assertThat(repository.find("ABCD")).isEmpty();
        assertThat(repository.compareAndDelete("ABCD", room.getVersion()))
                .isEqualTo(RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND);
    }

    @Test
    @DisplayName("중복 방 저장 시 기존 Redis 상태를 유지한다.")
    void saveIfAbsent_중복방은기존Redis상태를유지한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom firstRoom = GameRoom.create("ABCD", new Player("첫 번째"), Duration.ofMinutes(10));
        GameRoom duplicateRoom = GameRoom.create("ABCD", new Player("두 번째"), Duration.ofMinutes(10));
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
        GameRoom firstRoom = GameRoom.create("ABCD", new Player("첫 번째"), Duration.ofMinutes(10));
        GameRoom secondRoom = GameRoom.create("ABCD", new Player("두 번째"), Duration.ofMinutes(10));
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
        GameRoom original = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
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
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(repository)
        );
        gameRoomRepository.saveIfAbsent(room);

        // when
        gameRoomRepository.remove(room);

        // then
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        assertThat(repository.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("상태 변경 중 방이 제거되면 Redis 상태도 삭제한다.")
    void update_상태변경중방이제거되면Redis상태를삭제한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(repository)
        );
        gameRoomRepository.saveIfAbsent(room);

        // when
        gameRoomRepository.update("ABCD", currentRoom -> {
            gameRoomRepository.remove(currentRoom);
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
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
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
            return RoomUpdate.changed("updated");
        });

        // then
        assertThat(result).contains("updated");
        assertThat(operationInvoked).isTrue();
        assertThat(repository.find("ABCD")).contains(GameRoomState.from(room));
        assertThat(repository.find("ABCD").orElseThrow().version()).isEqualTo(2L);
    }

    private GameRoomRepository createCasGameRoomRepository(
            GameRegistry registry,
            GameRoomStateChangePublisher publisher
    ) {
        return new GameRoomRepository(
                registry,
                Optional.of(repository),
                Optional.of(publisher),
                event -> {
                }
        );
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
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
        GameRoom room = GameRoom.create("ABCD", host, Duration.ofMinutes(10));
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
