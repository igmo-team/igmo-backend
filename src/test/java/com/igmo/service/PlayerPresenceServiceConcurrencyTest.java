package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GameRoom;
import com.igmo.monitoring.GameMetrics;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.JoinGameResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class PlayerPresenceServiceConcurrencyTest {

    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final GameRegistry gameRegistry = new GameRegistry();
    private final RoomCodeGenerator roomCodeGenerator = mock(RoomCodeGenerator.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final TaskScheduler disconnectGraceScheduler = mock(TaskScheduler.class);
    private final TaskScheduler gamePhaseDeadlineScheduler = mock(TaskScheduler.class);
    private final TaskScheduler imageGenerationCompletionScheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> scheduledRemoval = mock(ScheduledFuture.class);
    private final GameRoomRepository gameRoomRepository = new GameRoomRepository(gameRegistry);
    private final GameEventPublisher eventPublisher = new GameEventPublisher(messagingTemplate, gameMetrics);
    private final GamePhaseScheduler gamePhaseScheduler =
            new GamePhaseScheduler(gamePhaseDeadlineScheduler, imageGenerationCompletionScheduler);
    private final GameLobbyService gameLobbyService =
            new GameLobbyService(gameRoomRepository, roomCodeGenerator, eventPublisher);
    private final PlayerPresenceService playerPresenceService =
            new PlayerPresenceService(
                    gameRoomRepository,
                    gamePhaseScheduler,
                    eventPublisher,
                    disconnectGraceScheduler);
    @BeforeEach
    void 스케줄러가_예약_future를_반환하도록_설정한다() {
        ReflectionTestUtils.setField(playerPresenceService, "disconnectGrace", Duration.ofSeconds(3));
        given(disconnectGraceScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledRemoval);
    }

    @Test
    @DisplayName("만료 삭제와 재연결 취소가 경합해도 취소 여부와 플레이어 상태가 일치한다.")
    void pendingRemoval_만료와_취소가_경합해도_일관된_상태로_수렴한다() throws Exception {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");
        JoinGameResponse joined = gameLobbyService.joinGame("ABCD", "참가자");
        playerPresenceService.handleDisconnect("ABCD", joined.playerId());
        Runnable removal = captureScheduledRemoval();
        AtomicBoolean canceled = new AtomicBoolean();
        given(scheduledRemoval.cancel(false)).willAnswer(invocation -> {
            canceled.set(true);
            return true;
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        Future<?> expirationTask = executor.submit(() -> {
            await(start);
            removal.run();
        });
        Future<?> cancellationTask = executor.submit(() -> {
            await(start);
            playerPresenceService.cancelPendingRemoval("ABCD", joined.playerId());
        });
        start.countDown();
        try {
            expirationTask.get(10, TimeUnit.SECONDS);
            cancellationTask.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasPlayer(joined.playerId())).isEqualTo(canceled.get());
            softly.assertThat(room.getPlayers()).hasSize(canceled.get() ? 2 : 1);
        });
    }

    @Test
    @DisplayName("재연결 취소가 예약 키를 먼저 제거하면 실행 중인 만료 작업도 플레이어를 제거하지 않는다.")
    void pendingRemoval_취소가_선점하면_플레이어를_유지한다() throws Exception {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");
        JoinGameResponse joined = gameLobbyService.joinGame("ABCD", "참가자");
        playerPresenceService.handleDisconnect("ABCD", joined.playerId());
        Runnable removal = captureScheduledRemoval();
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch finishCancellation = new CountDownLatch(1);
        given(scheduledRemoval.cancel(false)).willAnswer(invocation -> {
            cancellationStarted.countDown();
            await(finishCancellation);
            return true;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> cancellationTask =
                executor.submit(() -> playerPresenceService.cancelPendingRemoval("ABCD", joined.playerId()));

        // when
        try {
            assertThat(cancellationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            removal.run();
        } finally {
            finishCancellation.countDown();
            try {
                cancellationTask.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .matches(room -> room.hasPlayer(joined.playerId()), "참가자가 방에 남아 있어야 한다");
        verify(scheduledRemoval).cancel(false);
    }

    private Runnable captureScheduledRemoval() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(disconnectGraceScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기 중 인터럽트되었습니다.", e);
        }
    }
}
