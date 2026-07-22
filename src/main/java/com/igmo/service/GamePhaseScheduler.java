package com.igmo.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class GamePhaseScheduler {

    private final TaskScheduler phaseDeadlineScheduler;
    private final TaskScheduler imageGenerationCompletionScheduler;
    private final Map<String, ScheduledFuture<?>> pendingPromptExpirations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingGuessExpirations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingVoteExpirations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingResultExpirations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingPlayingTransitions = new ConcurrentHashMap<>();

    public GamePhaseScheduler(
            @Qualifier("gamePhaseDeadlineScheduler") TaskScheduler phaseDeadlineScheduler,
            @Qualifier("imageGenerationCompletionScheduler") TaskScheduler imageGenerationCompletionScheduler
    ) {
        this.phaseDeadlineScheduler = phaseDeadlineScheduler;
        this.imageGenerationCompletionScheduler = imageGenerationCompletionScheduler;
    }

    public void schedulePrompt(String code, Instant deadline, Runnable task) {
        scheduleReplacing(code, deadline, task, pendingPromptExpirations, phaseDeadlineScheduler);
    }

    public void cancelPrompt(String code) {
        cancel(code, pendingPromptExpirations);
    }

    public void scheduleGuess(String code, Instant deadline, Runnable task) {
        scheduleReplacing(code, deadline, task, pendingGuessExpirations, phaseDeadlineScheduler);
    }

    public void cancelGuess(String code) {
        cancel(code, pendingGuessExpirations);
    }

    public void scheduleVote(String code, Instant deadline, Runnable task) {
        scheduleReplacing(code, deadline, task, pendingVoteExpirations, phaseDeadlineScheduler);
    }

    public void cancelVote(String code) {
        cancel(code, pendingVoteExpirations);
    }

    public void scheduleResult(String code, Instant deadline, Runnable task) {
        scheduleReplacing(code, deadline, task, pendingResultExpirations, phaseDeadlineScheduler);
    }

    public void cancelResult(String code) {
        cancel(code, pendingResultExpirations);
    }

    public void schedulePlayingTransition(String code, Instant deadline, Runnable task) {
        ScheduledFuture<?> future = imageGenerationCompletionScheduler.schedule(
                guardedTask(code, task, pendingPlayingTransitions),
                deadline);
        ScheduledFuture<?> existing = pendingPlayingTransitions.putIfAbsent(code, future);
        if (existing != null) {
            future.cancel(false);
        }
    }

    public void cancelPlayingTransition(String code) {
        cancel(code, pendingPlayingTransitions);
    }

    public void cancelAll(String code) {
        cancelPrompt(code);
        cancelGuess(code);
        cancelVote(code);
        cancelResult(code);
        cancelPlayingTransition(code);
    }

    private void scheduleReplacing(
            String code,
            Instant deadline,
            Runnable task,
            Map<String, ScheduledFuture<?>> pendingTasks,
            TaskScheduler scheduler
    ) {
        ScheduledFuture<?> future = scheduler.schedule(guardedTask(code, task, pendingTasks), deadline);
        ScheduledFuture<?> previous = pendingTasks.put(code, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private Runnable guardedTask(
            String code,
            Runnable task,
            Map<String, ScheduledFuture<?>> pendingTasks
    ) {
        return () -> {
            if (pendingTasks.remove(code) != null) {
                task.run();
            }
        };
    }

    private void cancel(String code, Map<String, ScheduledFuture<?>> pendingTasks) {
        ScheduledFuture<?> future = pendingTasks.remove(code);
        if (future != null) {
            future.cancel(false);
        }
    }
}
