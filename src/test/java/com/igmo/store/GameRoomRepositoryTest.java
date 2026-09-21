package com.igmo.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameStartPolicy;
import com.igmo.domain.Player;
import com.igmo.service.exception.RoomNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GameRoomRepositoryTest {

    @Test
    @DisplayName("로비 만료 시 같은 방의 JVM 상태와 Redis 상태를 함께 제거한다.")
    void removeLobbyIfExpired_만료된로비를양쪽에서제거한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        RedisGameRoomStateRepository redisRepository = mock(RedisGameRoomStateRepository.class);
        GameRoomRepository repository = new GameRoomRepository(
                gameRegistry,
                Optional.of(redisRepository)
        );
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        gameRegistry.saveIfAbsent(room);
        ReflectionTestUtils.setField(room, "lobbyDeadline", Instant.MIN);

        // when
        boolean removed = repository.removeLobbyIfExpired(room, Instant.now());

        // then
        assertThat(removed).isTrue();
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(redisRepository).delete("ABCD");
    }

    @Test
    @DisplayName("로비 만료와 게임 시작이 경쟁하면 먼저 시작된 게임을 만료 작업이 제거하지 않는다.")
    void removeLobbyIfExpired_게임시작과경쟁해도시작된방을제거하지않는다()
            throws Exception {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoomRepository repository = new GameRoomRepository(gameRegistry);
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host, GameStartPolicy.local(), Duration.ofMinutes(10));
        gameRegistry.saveIfAbsent(room);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        Future<?> start = executor.submit(() -> repository.update("ABCD", currentRoom -> {
            operationEntered.countDown();
            await(releaseOperation);
            currentRoom.start(host.getId(), Instant.now(), Duration.ofSeconds(1));
            return null;
        }));
        assertThat(operationEntered.await(1, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> expiration = executor.submit(
                () -> repository.removeLobbyIfExpired(room, room.getLobbyDeadline().plusSeconds(1))
        );
        releaseOperation.countDown();

        // then
        start.get(1, TimeUnit.SECONDS);
        assertThat(expiration.get(1, TimeUnit.SECONDS)).isFalse();
        assertThat(gameRegistry.find("ABCD").orElseThrow().getPhase())
                .isEqualTo(GamePhase.GENERATING);
        executor.shutdownNow();
    }

    @Test
    @DisplayName("만료된 로비는 게임 시작 요청보다 먼저 제거된다.")
    void update_만료된로비는게임시작을거절한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        GameRoomRepository repository = new GameRoomRepository(gameRegistry);
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host, GameStartPolicy.local(), Duration.ofMinutes(10));
        gameRegistry.saveIfAbsent(room);
        ReflectionTestUtils.setField(room, "lobbyDeadline", Instant.MIN);

        // when // then
        assertThatThrownBy(() -> repository.update("ABCD", currentRoom -> {
            currentRoom.start(host.getId(), Instant.now(), Duration.ofSeconds(1));
            return null;
        })).isInstanceOf(RoomNotFoundException.class);
        assertThat(gameRegistry.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("Redis에서 복원한 만료 로비는 JVM에 등록하지 않고 Redis에서도 삭제한다.")
    void restore_만료된로비는복원하지않는다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        RedisGameRoomStateRepository redisRepository = mock(RedisGameRoomStateRepository.class);
        GameRoomRepository repository = new GameRoomRepository(
                gameRegistry,
                Optional.of(redisRepository)
        );
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        ReflectionTestUtils.setField(room, "lobbyDeadline", Instant.MIN);
        when(redisRepository.restore("ABCD")).thenReturn(Optional.of(room));

        // when
        Optional<GameRoom> restored = repository.restore("ABCD");

        // then
        assertThat(restored).isEmpty();
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(redisRepository).delete("ABCD");
    }

    @Test
    @DisplayName("Redis 삭제가 실패해도 만료된 로비는 JVM에서 제거한다.")
    void removeLobbyIfExpired_Redis삭제실패에도JVM에서제거한다() {
        // given
        GameRegistry gameRegistry = new GameRegistry();
        RedisGameRoomStateRepository redisRepository = mock(RedisGameRoomStateRepository.class);
        GameRoomRepository repository = new GameRoomRepository(
                gameRegistry,
                Optional.of(redisRepository)
        );
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        gameRegistry.saveIfAbsent(room);
        ReflectionTestUtils.setField(room, "lobbyDeadline", Instant.MIN);
        willThrow(new IllegalStateException("Redis unavailable"))
                .given(redisRepository)
                .delete("ABCD");

        // when // then
        assertThatThrownBy(() -> repository.removeLobbyIfExpired(room, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis unavailable");
        assertThat(gameRegistry.find("ABCD")).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("동시성 테스트가 시간 내에 진행되지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
