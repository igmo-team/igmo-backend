package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.store.GameRegistry;
import com.igmo.web.WebSocketSessionRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class GameDrainLifecycleTest {

    private final GameRegistry gameRegistry = new GameRegistry();
    private final WebSocketSessionRegistry webSocketSessionRegistry = mock(WebSocketSessionRegistry.class);
    private final GameDrainLifecycle lifecycle =
            new GameDrainLifecycle(gameRegistry, webSocketSessionRegistry);

    @Nested
    @DisplayName("종료 제한 시간 검증")
    class ShutdownTimeout {

        @BeforeEach
        void 시간을_설정한다() {
            ReflectionTestUtils.setField(lifecycle, "promptDuration", Duration.ofSeconds(10));
            ReflectionTestUtils.setField(lifecycle, "guessDuration", Duration.ofSeconds(20));
            ReflectionTestUtils.setField(lifecycle, "voteDuration", Duration.ofSeconds(30));
            ReflectionTestUtils.setField(lifecycle, "resultDuration", Duration.ofSeconds(40));
            ReflectionTestUtils.setField(lifecycle, "imageGenerationCompletionDelay", Duration.ofSeconds(5));
        }

        @ParameterizedTest
        @ValueSource(longs = {752, 753})
        @DisplayName("최대 게임 시간 753초 이하의 종료 제한은 예외로 거부한다.")
        void 최대_시간_이하는_거부한다(long seconds) {
            // given
            ReflectionTestUtils.setField(lifecycle, "shutdownTimeout", Duration.ofSeconds(seconds));

            // when & then
            assertThatThrownBy(lifecycle::validateShutdownTimeout)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                            "spring.lifecycle.timeout-per-shutdown-phase는 최대 게임 Drain 시간보다 길어야 합니다. 최대 게임 Drain 시간: PT12M33S");
        }

        @Test
        @DisplayName("최대 게임 시간보다 1나노초 긴 종료 제한은 허용한다.")
        void 최대_시간_초과는_허용한다() {
            // given
            ReflectionTestUtils.setField(lifecycle, "shutdownTimeout", Duration.ofSeconds(753).plusNanos(1));

            // when & then
            assertThatCode(lifecycle::validateShutdownTimeout).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("검사 회차를 제어하는 Drain 테스트")
    class ControlledDrain {

        private final ScheduledExecutorService monitor = mock(ScheduledExecutorService.class);
        private final ScheduledFuture<?> scheduledCheck = mock(ScheduledFuture.class);
        private final Runnable callback = mock(Runnable.class);
        private final ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);

        @BeforeEach
        void 스케줄러를_제어한다() {
            ScheduledExecutorService original =
                    (ScheduledExecutorService) ReflectionTestUtils.getField(lifecycle, "drainMonitor");
            original.shutdownNow();
            ReflectionTestUtils.setField(lifecycle, "drainMonitor", monitor);
            org.mockito.Mockito.doReturn(scheduledCheck).when(monitor)
                    .scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("시작 전 stop은 콜백을 즉시 실행하고 검사를 예약하지 않는다.")
        void 시작_전에는_즉시_완료한다() {
            // when
            lifecycle.stop(callback);

            // then
            verify(callback).run();
            verifyNoInteractions(monitor, webSocketSessionRegistry);
            assertThat(lifecycle.isRunning()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = GamePhase.class, names = {"LOBBY", "GENERATING", "PLAYING", "VOTING", "RESULTS"})
        @DisplayName("모든 진행 단계는 종료 통지 전까지 전체 연결과 콜백을 유지한다.")
        void 진행_단계는_종료_통지를_기다린다(GamePhase phase) {
            // given
            saveRoom("ABCD", phase);
            lifecycle.start();
            assertThat(lifecycle.isRunning()).isTrue();

            // when
            lifecycle.stop(callback);
            runCheck();

            // then
            assertThat(lifecycle.isRunning()).isFalse();
            verifyNoInteractions(callback);
            verify(webSocketSessionRegistry, never()).closeAll();
        }

        @Test
        @DisplayName("여러 게임 중 하나만 끝나면 기다리고 모두 끝나면 완료한다.")
        void 모든_게임의_종료를_기다린다() {
            // given
            saveRoom("ABCD", GamePhase.PLAYING);
            saveRoom("EFGH", GamePhase.VOTING);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            lifecycle.onGameEnded("ABCD");
            runCheck();

            // then
            verifyNoInteractions(callback);
            verify(webSocketSessionRegistry, never()).closeAll();
            lifecycle.onGameEnded("EFGH");
            runCheck();
            verify(callback).run();
        }

        @Test
        @DisplayName("진행 중 게임이 끝나도 대기방이 남으면 연결과 Drain을 유지한다.")
        void 대기방과_진행_중_게임을_함께_기다린다() {
            // given
            saveRoom("LOBBY", GamePhase.LOBBY);
            saveRoom("GAME", GamePhase.PLAYING);
            lifecycle.start();

            // when
            lifecycle.stop(callback);
            lifecycle.onGameEnded("GAME");
            runCheck();

            // then
            verify(webSocketSessionRegistry, never()).close(any());
            verify(webSocketSessionRegistry, never()).closeAll();
            verifyNoInteractions(callback);
            removeRoom("LOBBY");
            runCheck();
            verify(callback).run();
        }

        @Test
        @DisplayName("연결이 없어도 대기방이 레지스트리에서 삭제될 때까지 기다린다.")
        void 연결_없는_대기방도_삭제될_때까지_기다린다() {
            // given
            saveRoom("LOBBY", GamePhase.LOBBY);
            lifecycle.start();

            // when
            lifecycle.stop(callback);
            runCheck();

            // then
            verify(webSocketSessionRegistry, never()).close(any());
            verify(webSocketSessionRegistry, never()).closeAll();
            verifyNoInteractions(callback);
            removeRoom("LOBBY");
            runCheck();
            verify(callback).run();
        }

        @Test
        @DisplayName("Drain 중 대기방에서 게임을 시작하면 최종 종료 통지까지 연결을 유지한다.")
        void 대기방에서_시작한_게임의_완료를_기다린다() {
            // given
            GameRoom room = saveRoom("LOBBY", GamePhase.LOBBY);
            lifecycle.start();
            lifecycle.stop(callback);
            runCheck();

            // when
            when(room.getPhase()).thenReturn(GamePhase.PLAYING);
            runCheck();
            when(room.getPhase()).thenReturn(GamePhase.ENDED);
            runCheck();

            // then
            verifyNoInteractions(callback);
            verify(webSocketSessionRegistry, never()).close(any());
            verify(webSocketSessionRegistry, never()).closeAll();
            lifecycle.onGameEnded("LOBBY");
            runCheck();
            verify(callback).run();
            verify(webSocketSessionRegistry).closeAll();
        }

        @Test
        @DisplayName("반복 검사에서 발견한 대기방도 삭제될 때까지 기다린다.")
        void 새로_발견한_대기방도_유지한다() {
            // given
            saveRoom("GAME", GamePhase.PLAYING);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            saveRoom("LOBBY", GamePhase.LOBBY);
            lifecycle.onGameEnded("GAME");
            runCheck();

            // then
            verifyNoInteractions(callback);
            verify(webSocketSessionRegistry, never()).close(any());
            verify(webSocketSessionRegistry, never()).closeAll();
            removeRoom("LOBBY");
            runCheck();
            verify(callback).run();
        }

        @Test
        @DisplayName("Drain 시작 전에 이미 종료된 방은 대기 대상이 아니다.")
        void 이미_종료된_방은_기다리지_않는다() {
            // given
            saveRoom("ABCD", GamePhase.ENDED);
            lifecycle.start();

            // when
            lifecycle.stop(callback);
            runCheck();

            // then
            verify(callback).run();
        }

        @Test
        @DisplayName("진행 중 방이 ENDED로 바뀌어도 종료 통지가 오기 전에는 기다린다.")
        void ENDED_변경만으로_완료하지_않는다() {
            // given
            GameRoom room = saveRoom("ABCD", GamePhase.PLAYING);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            when(room.getPhase()).thenReturn(GamePhase.ENDED);
            runCheck();

            // then
            verifyNoInteractions(callback);
            lifecycle.onGameEnded("ABCD");
            runCheck();
            verify(callback).run();
        }

        @Test
        @DisplayName("진행 중 방이 삭제되면 종료 통지 없이 대기에서 제외한다.")
        void 삭제된_방은_대기에서_제외한다() {
            // given
            saveRoom("ABCD", GamePhase.PLAYING);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            removeRoom("ABCD");
            runCheck();

            // then
            verify(callback).run();
        }

        @Test
        @DisplayName("반복 검사에서 새로 발견한 진행 중 방도 기다린다.")
        void 새로_발견한_게임을_대기에_추가한다() {
            // given
            saveRoom("ABCD", GamePhase.PLAYING);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            saveRoom("EFGH", GamePhase.PLAYING);
            lifecycle.onGameEnded("ABCD");
            runCheck();

            // then
            verifyNoInteractions(callback);
            lifecycle.onGameEnded("EFGH");
            runCheck();
            verify(callback).run();
        }

        @Test
        @DisplayName("게임이 없어도 연결이 남으면 기다리고 전체 종료 요청은 반복하지 않는다.")
        void 연결이_0이_될_때까지_기다린다() {
            // given
            when(webSocketSessionRegistry.count()).thenReturn(2);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            runCheck();
            runCheck();

            // then
            verifyNoInteractions(callback);
            verify(webSocketSessionRegistry).closeAll();
            when(webSocketSessionRegistry.count()).thenReturn(0);
            runCheck();
            runCheck();
            verify(callback).run();
            verify(scheduledCheck).cancel(false);
            verify(webSocketSessionRegistry).closeAll();
        }

        @Test
        @DisplayName("Drain 전 종료 통지는 이후 진행 중 게임을 대기에서 제외하지 않는다.")
        void Drain_전_종료_통지는_무시한다() {
            // given
            saveRoom("ABCD", GamePhase.PLAYING);
            lifecycle.onGameEnded("ABCD");
            lifecycle.start();

            // when
            lifecycle.stop(callback);
            runCheck();

            // then
            verifyNoInteractions(callback);
        }

        @Test
        @DisplayName("인자 없는 stop도 전체 연결 종료 검사를 예약한다.")
        void 인자_없는_stop도_Drain을_시작한다() {
            // given
            lifecycle.start();

            // when
            lifecycle.stop();
            runCheck();

            // then
            verify(webSocketSessionRegistry).closeAll();
            verify(scheduledCheck).cancel(false);
        }

        @Test
        @DisplayName("destroy는 예약 검사를 취소하고 모니터를 종료하되 완료 콜백은 실행하지 않는다.")
        void destroy는_검사_자원을_정리한다() {
            // given
            saveRoom("ABCD", GamePhase.PLAYING);
            lifecycle.start();
            lifecycle.stop(callback);

            // when
            lifecycle.destroy();

            // then
            verify(scheduledCheck).cancel(false);
            verify(monitor).shutdownNow();
            verifyNoInteractions(callback);
        }

        private void runCheck() {
            org.mockito.Mockito.verify(monitor, org.mockito.Mockito.atLeastOnce())
                    .scheduleWithFixedDelay(check.capture(), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
            check.getValue().run();
        }
    }

    private GameRoom saveRoom(String code, GamePhase phase) {
        GameRoom room = mock(GameRoom.class);
        when(room.getCode()).thenReturn(code);
        when(room.getPhase()).thenReturn(phase);
        gameRegistry.saveIfAbsent(room);
        return room;
    }

    private void removeRoom(String code) {
        gameRegistry.find(code).ifPresent(gameRegistry::removeIfSame);
    }

    @AfterEach
    void drain_monitor를_종료한다() throws Exception {
        lifecycle.destroy();
    }

    @Test
    @DisplayName("실제 모니터에서 대기방 연결을 유지하고 방 삭제 후 종료한다.")
    void stop_실제_모니터가_대기방_삭제를_기다린다() throws Exception {
        // given
        GameRoom room = saveRoom("LOBBY", GamePhase.LOBBY);
        CountDownLatch inspected = new CountDownLatch(2);
        when(room.getPhase()).thenAnswer(invocation -> {
            inspected.countDown();
            return GamePhase.LOBBY;
        });
        CountDownLatch completed = new CountDownLatch(1);
        lifecycle.start();

        // when
        lifecycle.stop(completed::countDown);

        // then
        assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.getCount()).isOne();
        verify(webSocketSessionRegistry, never()).close(any());
        verify(webSocketSessionRegistry, never()).closeAll();
        removeRoom("LOBBY");
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        verify(webSocketSessionRegistry).closeAll();
    }

    @Test
    @DisplayName("진행 중인 게임은 종료 이벤트 전까지 shutdown callback을 호출하지 않는다.")
    void stop_진행_중인_게임은_종료_이벤트_후에_callback을_호출한다() throws Exception {
        // given
        GameRoom room = mock(GameRoom.class);
        when(room.getCode()).thenReturn("ABCD");
        when(room.getPhase()).thenReturn(GamePhase.PLAYING);
        gameRegistry.saveIfAbsent(room);
        when(webSocketSessionRegistry.count()).thenReturn(0);
        CountDownLatch callback = new CountDownLatch(1);
        lifecycle.start();

        // when
        lifecycle.stop(callback::countDown);

        // then
        assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse();
        lifecycle.onGameEnded("ABCD");
        assertThat(callback.await(2, TimeUnit.SECONDS)).isTrue();
        verify(webSocketSessionRegistry).closeAll();
    }

    @Test
    @DisplayName("게임이 없으면 WebSocket 세션 정리 후 즉시 shutdown callback을 호출한다.")
    void stop_게임이_없으면_세션_정리_후_callback을_호출한다() throws Exception {
        // given
        when(webSocketSessionRegistry.count()).thenReturn(0);
        CountDownLatch callback = new CountDownLatch(1);
        lifecycle.start();

        // when
        lifecycle.stop(callback::countDown);

        // then
        assertThat(callback.await(2, TimeUnit.SECONDS)).isTrue();
        verify(webSocketSessionRegistry).closeAll();
    }
}
