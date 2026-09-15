package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.store.GameRegistry;
import com.igmo.web.WebSocketSessionRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameDrainLifecycle implements SmartLifecycle, DisposableBean {

    private static final long DRAIN_CHECK_INTERVAL_MILLIS = 250;

    private final GameRegistry gameRegistry;
    private final WebSocketSessionRegistry webSocketSessionRegistry;
    private final ScheduledExecutorService drainMonitor =
            Executors.newSingleThreadScheduledExecutor(new DrainThreadFactory());
    private final Object monitor = new Object();
    private final Set<String> pendingGameCodes = new HashSet<>();
    private final Set<String> completedGameCodes = new HashSet<>();

    @Value("${igmo.game.prompt-duration}")
    private Duration promptDuration;
    @Value("${igmo.game.guess-duration}")
    private Duration guessDuration;
    @Value("${igmo.game.vote-duration}")
    private Duration voteDuration;
    @Value("${igmo.game.result-duration}")
    private Duration resultDuration;
    @Value("${igmo.game.image-generation-completion-delay}")
    private Duration imageGenerationCompletionDelay;
    @Value("${spring.lifecycle.timeout-per-shutdown-phase}")
    private Duration shutdownTimeout;

    private volatile boolean isRunning;
    private boolean isDraining;
    private boolean sessionsClosing;
    private Runnable stopCallback;
    private ScheduledFuture<?> drainCheck;

    @PostConstruct
    void validateShutdownTimeout() {
        Duration maximumGameDuration = promptDuration
                .plus(GameRoom.PROMPT_SUBMISSION_GRACE_PERIOD)
                .plus(imageGenerationCompletionDelay)
                .plus(guessDuration
                        .plus(GameRoom.GUESS_SUBMISSION_GRACE_PERIOD)
                        .plus(voteDuration)
                        .plus(resultDuration)
                        .multipliedBy(GameRoom.MAX_PLAYERS));
        if (shutdownTimeout.compareTo(maximumGameDuration) <= 0) {
            throw new IllegalStateException(
                    "spring.lifecycle.timeout-per-shutdown-phase는 최대 게임 Drain 시간보다 길어야 합니다. 최대 게임 Drain 시간: "
                            + maximumGameDuration);
        }
    }

    @Override
    public void start() {
        isRunning = true;
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        synchronized (monitor) {
            if (!isRunning) {
                callback.run();
                return;
            }
            isRunning = false;
            isDraining = true;
            stopCallback = callback;
            pendingGameCodes.clear();
            completedGameCodes.clear();
            refreshPendingGames();
        }

        drainCheck = drainMonitor.scheduleWithFixedDelay(
                this::checkDrain,
                0,
                DRAIN_CHECK_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
        log.info("게임 Drain을 시작합니다. pendingGameCount={}", pendingGameCodes.size());
    }

    @Override
    public int getPhase() {
        // Spring scheduler 기본 phase(MAX_VALUE / 2)보다 먼저 멈춰야 기존 deadline task가 계속 실행된다.
        return SmartLifecycle.DEFAULT_PHASE;
    }

    public void onGameEnded(String roomCode) {
        synchronized (monitor) {
            if (!isDraining) {
                return;
            }
            completedGameCodes.add(roomCode);
            pendingGameCodes.remove(roomCode);
        }
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void destroy() {
        ScheduledFuture<?> currentDrainCheck = drainCheck;
        if (currentDrainCheck != null) {
            currentDrainCheck.cancel(false);
        }
        drainMonitor.shutdownNow();
    }

    private void checkDrain() {
        boolean shouldCloseAllSessions = false;
        Runnable callback = null;

        synchronized (monitor) {
            if (!isDraining) {
                return;
            }

            refreshPendingGames();
            if (pendingGameCodes.isEmpty() && !sessionsClosing) {
                sessionsClosing = true;
                shouldCloseAllSessions = true;
            }
            if (sessionsClosing && pendingGameCodes.isEmpty()
                    && webSocketSessionRegistry.count() == 0) {
                isDraining = false;
                callback = stopCallback;
                stopCallback = null;
            }
        }

        if (shouldCloseAllSessions) {
            webSocketSessionRegistry.closeAll();
        }
        if (callback != null) {
            finishDrain(callback);
        }
    }

    private void refreshPendingGames() {
        for (GameRoom room : gameRegistry.snapshot()) {
            if (room.getPhase() != GamePhase.ENDED) {
                pendingGameCodes.add(room.getCode());
            }
        }
        pendingGameCodes.removeIf(roomCode -> gameRegistry.find(roomCode).isEmpty()
                || completedGameCodes.contains(roomCode));
    }

    private void finishDrain(Runnable callback) {
        ScheduledFuture<?> currentDrainCheck = drainCheck;
        if (currentDrainCheck != null) {
            currentDrainCheck.cancel(false);
        }
        log.info("게임 Drain이 완료되었습니다.");
        callback.run();
    }

    private static final class DrainThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "game-drain-monitor");
            thread.setDaemon(true);
            return thread;
        }
    }
}
