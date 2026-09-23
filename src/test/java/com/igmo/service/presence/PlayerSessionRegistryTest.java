package com.igmo.service.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerSessionRegistryTest {

    private final PlayerSessionRegistry registry = new PlayerSessionRegistry();
    private final PlayerKey playerKey = new PlayerKey("ABCD", "player-1");

    @Test
    @DisplayName("방에 속한 여러 플레이어의 모든 세션을 조회한다.")
    void sessionIdsForRoom_여러_플레이어의_모든_세션을_반환한다() {
        // given
        registry.register(playerKey, "session-1");
        registry.register(playerKey, "session-2");
        registry.register(new PlayerKey("ABCD", "player-2"), "session-3");

        // when
        var sessionIds = registry.sessionIdsForRoom("ABCD");

        // then
        assertThat(sessionIds).containsExactlyInAnyOrder("session-1", "session-2", "session-3");
    }

    @Test
    @DisplayName("플레이어 ID가 같아도 다른 방의 세션은 조회에서 제외한다.")
    void sessionIdsForRoom_다른_방의_세션은_제외한다() {
        // given
        registry.register(playerKey, "session-1");
        registry.register(new PlayerKey("EFGH", "player-1"), "other-room-session");

        // when
        var sessionIds = registry.sessionIdsForRoom("ABCD");

        // then
        assertThat(sessionIds).containsExactly("session-1");
    }

    @Test
    @DisplayName("세션이 없는 방을 조회하면 빈 집합을 반환한다.")
    void sessionIdsForRoom_세션이_없는_방은_빈_집합을_반환한다() {
        // given
        registry.register(playerKey, "session-1");

        // when
        var sessionIds = registry.sessionIdsForRoom("EFGH");

        // then
        assertThat(sessionIds).isEmpty();
    }

    @Test
    @DisplayName("해제한 세션과 정리된 플레이어의 세션은 조회에서 제외한다.")
    void sessionIdsForRoom_해제하거나_정리한_세션은_제외한다() {
        // given
        PlayerKey otherPlayer = new PlayerKey("ABCD", "player-2");
        registry.register(playerKey, "session-1");
        registry.register(playerKey, "session-2");
        registry.register(otherPlayer, "session-3");
        registry.unregister(playerKey, "session-1");
        registry.clear(otherPlayer);

        // when
        var sessionIds = registry.sessionIdsForRoom("ABCD");

        // then
        assertThat(sessionIds).containsExactly("session-2");
    }

    @Test
    @DisplayName("같은 플레이어의 여러 세션을 등록하고 마지막 세션 해제 시 key를 제거한다.")
    void registerAndUnregister_여러_세션을_추적하고_마지막_해제_시_key를_제거한다() {
        // when
        registry.register(playerKey, "session-1");
        registry.register(playerKey, "session-1");
        registry.register(playerKey, "session-2");

        // then
        assertThat(registry.unregister(playerKey, "session-1")).isFalse();
        assertThat(registry.hasActiveSession(playerKey)).isTrue();
        assertThat(registry.unregister(playerKey, "session-2")).isTrue();
        assertThat(registry.hasActiveSession(playerKey)).isFalse();
    }

    @Test
    @DisplayName("알 수 없거나 중복된 세션 해제는 활성 세션과 key를 훼손하지 않는다.")
    void unregister_알수없거나_중복된_세션은_상태를_훼손하지_않는다() {
        // given
        registry.register(playerKey, "session-1");
        registry.register(playerKey, "session-2");

        // when & then
        assertThat(registry.unregister(playerKey, "unknown-session")).isFalse();
        assertThat(registry.unregister(playerKey, "session-1")).isFalse();
        assertThat(registry.unregister(playerKey, "session-1")).isFalse();
        assertThat(registry.hasActiveSession(playerKey)).isTrue();
        assertThat(registry.unregister(playerKey, "session-2")).isTrue();
        assertThat(registry.hasActiveSession(playerKey)).isFalse();
    }

    @Test
    @DisplayName("마지막 세션 해제 후 동일 플레이어의 재연결은 key와 새 세션을 다시 등록한다.")
    void reconnect_마지막_해제_후_key와_세션을_다시_등록한다() {
        // given
        registry.register(playerKey, "session-1");
        registry.unregister(playerKey, "session-1");

        // when
        registry.register(playerKey, "session-2");

        // then
        assertThat(registry.hasActiveSession(playerKey)).isTrue();
        assertThat(registry.unregister(playerKey, "session-2")).isTrue();
        assertThat(registry.hasActiveSession(playerKey)).isFalse();
    }

    @Test
    @DisplayName("동시에 세션을 등록하고 해제해도 빈 key를 남기지 않는다.")
    void concurrentRegisterAndUnregister_빈_key를_남기지_않는다() throws Exception {
        // given
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> registerFirst = executor.submit(() -> registerAfter(start, "session-1"));
        Future<?> registerSecond = executor.submit(() -> registerAfter(start, "session-2"));

        // when
        start.countDown();
        try {
            registerFirst.get(10, TimeUnit.SECONDS);
            registerSecond.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(registry.hasActiveSession(playerKey)).isTrue();

        // then
        assertThat(registry.unregister(playerKey, "session-1")).isFalse();
        assertThat(registry.unregister(playerKey, "session-2")).isTrue();
        assertThat(registry.hasActiveSession(playerKey)).isFalse();
    }

    private void registerAfter(CountDownLatch start, String sessionId) {
        await(start);
        registry.register(playerKey, sessionId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("세션 registry 테스트 대기 중 인터럽트되었습니다.", e);
        }
    }
}
