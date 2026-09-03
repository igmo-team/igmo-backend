package com.igmo.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class PlayerSessionRegistry {

    private static final int LOCK_STRIPE_COUNT = 64;

    private final Map<PlayerKey, Set<String>> activeSessions = new ConcurrentHashMap<>();
    private final ReentrantLock[] playerLocks = createLocks();

    public void register(PlayerKey playerKey, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        withPlayerLock(
                playerKey,
                () -> activeSessions.computeIfAbsent(
                        playerKey,
                        key -> ConcurrentHashMap.newKeySet()
                ).add(sessionId)
        );
    }

    public boolean unregister(PlayerKey playerKey, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return withPlayerLock(
                playerKey,
                () -> {
                    boolean[] becameEmpty = {false};
                    activeSessions.computeIfPresent(
                            playerKey,
                            (key, sessions) -> {
                                sessions.remove(sessionId);
                                if (sessions.isEmpty()) {
                                    becameEmpty[0] = true;
                                    return null;
                                }
                                return sessions;
                            }
                    );
                    return becameEmpty[0];
                });
    }

    public boolean hasActiveSession(PlayerKey playerKey) {
        return withPlayerLock(playerKey, () -> activeSessions.containsKey(playerKey));
    }

    public void clear(PlayerKey playerKey) {
        withPlayerLock(playerKey, () -> activeSessions.remove(playerKey));
    }

    public void withPlayerLock(PlayerKey playerKey, Runnable action) {
        ReentrantLock lock = lockFor(playerKey);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T withPlayerLock(PlayerKey playerKey, Supplier<T> action) {
        ReentrantLock lock = lockFor(playerKey);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(PlayerKey playerKey) {
        int lockIndex = Math.floorMod(playerKey.hashCode(), playerLocks.length);
        return playerLocks[lockIndex];
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }
}
