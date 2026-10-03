package com.igmo.store;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.service.GameRoomRestoredEvent;
import com.igmo.service.LobbyExpiredEvent;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.redis.GameRoomStateChangePublisher;
import com.igmo.store.redis.RedisGameRoomStateRepository;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class GameRoomRepository {

    private static final int MAX_CAS_ATTEMPTS = 3;

    private final GameRegistry gameRegistry;
    private final Optional<RedisGameRoomStateRepository> redisStateRepository;
    private final Optional<GameRoomStateChangePublisher> stateChangePublisher;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository,
            Optional<GameRoomStateChangePublisher> stateChangePublisher,
            ApplicationEventPublisher eventPublisher
    ) {
        this.gameRegistry = gameRegistry;
        this.redisStateRepository = redisStateRepository;
        this.stateChangePublisher = stateChangePublisher;
        this.eventPublisher = eventPublisher;
    }

    public GameRoomRepository(GameRegistry gameRegistry) {
        this(gameRegistry, Optional.empty(), Optional.empty(), event -> {
        });
    }

    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository
    ) {
        this(gameRegistry, redisStateRepository, Optional.empty(), event -> {
        });
    }

    public GameRoomRepository(
            GameRegistry gameRegistry,
            Optional<RedisGameRoomStateRepository> redisStateRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this(gameRegistry, redisStateRepository, Optional.empty(), eventPublisher);
    }

    public boolean saveIfAbsent(GameRoom room) {
        boolean saved = gameRegistry.saveIfAbsent(room);
        if (!saved) {
            return false;
        }
        if (redisStateRepository.isEmpty()) {
            return true;
        }

        boolean persisted;
        try {
            room.incrementVersion();
            persisted = redisStateRepository.orElseThrow().saveIfAbsent(room);
        } catch (RuntimeException exception) {
            gameRegistry.removeIfSame(room);
            throw exception;
        }
        if (persisted) {
            publishStateChanged(room.getCode(), room.getVersion(), List.of());
            return true;
        }
        gameRegistry.removeIfSame(room);
        return false;
    }

    public boolean remove(GameRoom room) {
        synchronized (room) {
            if (isDetached(room)) {
                return false;
            }
            List<GameRoomDelivery> deliveries = room.getPhase() == GamePhase.ENDED
                    ? List.of(GameRoomDelivery.gameResult(GameResultSnapshot.from(room)))
                    : List.of(GameRoomDelivery.roomRemoved());
            return removeAttached(room, deliveries);
        }
    }

    public Optional<GameRoom> restore(String code) {
        Optional<GameRoom> localRoom = restoreRoomByLocal(code);
        if (localRoom.isPresent()) {
            return localRoom;
        }
        return restoreRoomByRedis(code);
    }

    public void synchronizeFromRedis(String code) {
        synchronizeFromRedisAndGet(code);
    }

    public Optional<GameRoom> synchronizeFromRedisAndGet(String code) {
        return synchronizeFromRedisAndGet(code, Long.MAX_VALUE);
    }

    public Optional<GameRoom> synchronizeFromRedisAndGet(String code, long eventVersion) {
        if (redisStateRepository.isEmpty()) {
            return gameRegistry.find(code);
        }

        Optional<GameRoom> restored = redisStateRepository.orElseThrow().restore(code);
        if (restored.isEmpty()) {
            return removeLocalRoomIfNotNewer(code, eventVersion);
        }

        GameRoom room = restored.get();
        if (room.isLobbyExpired(Instant.now())) {
            return removeLocalRoomIfNotNewer(code, eventVersion);
        }
        boolean wasAbsent = gameRegistry.find(code).isEmpty();
        GameRoom currentRoom;
        if (eventVersion == Long.MAX_VALUE) {
            gameRegistry.replace(room);
            currentRoom = room;
        } else {
            currentRoom = gameRegistry.replaceIfNewer(room);
        }
        if (wasAbsent) {
            eventPublisher.publishEvent(new GameRoomRestoredEvent(currentRoom));
        }
        return Optional.of(currentRoom);
    }

    private Optional<GameRoom> removeLocalRoomIfNotNewer(String code, long eventVersion) {
        Optional<GameRoom> localRoom = gameRegistry.find(code);
        if (localRoom.isEmpty()) {
            return Optional.empty();
        }
        GameRoom room = localRoom.get();
        if (room.getVersion() <= eventVersion && gameRegistry.removeIfSame(room)) {
            return Optional.empty();
        }
        return gameRegistry.find(code);
    }

    private Optional<GameRoom> restoreRoomByLocal(String code) {
        Optional<GameRoom> existing = gameRegistry.find(code);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        GameRoom room = existing.get();
        if (removeLobbyIfExpired(room, Instant.now())) {
            return Optional.empty();
        }
        synchronized (room) {
            if (isDetached(room)) {
                return Optional.empty();
            }
        }
        return Optional.of(room);
    }

    private Optional<GameRoom> restoreRoomByRedis(String code) {
        Optional<GameRoom> restored = redisStateRepository.flatMap(
                repository -> repository.restore(code)
        );
        if (restored.isEmpty()) {
            return Optional.empty();
        }

        GameRoom room = restored.get();
        if (room.isLobbyExpired(Instant.now())) {
            if (deleteExpiredRestoredLobby(room)) {
                eventPublisher.publishEvent(new LobbyExpiredEvent(code));
            }
            return Optional.empty();
        }
        return registerRestoredRoom(room);
    }

    private Optional<GameRoom> registerRestoredRoom(GameRoom room) {
        if (!gameRegistry.saveIfAbsent(room)) {
            return gameRegistry.find(room.getCode());
        }

        eventPublisher.publishEvent(new GameRoomRestoredEvent(room)); //복원 이벤트 발행
        return Optional.of(room);
    }

    public boolean removeLobbyIfExpired(GameRoom room, Instant now) {
        if (redisStateRepository.isEmpty()) {
            synchronized (room) {
                if (isDetached(room) || !room.isLobbyExpired(now)) {
                    return false;
                }
                if (!removeAttached(room, List.of(GameRoomDelivery.lobbyExpired()))) {
                    return false;
                }
            }
            eventPublisher.publishEvent(new LobbyExpiredEvent(room.getCode()));
            return true;
        }

        synchronized (room) {
            if (isDetached(room) || !room.isLobbyExpired(now)) {
                return false;
            }
        }
        RedisGameRoomStateRepository repository = redisStateRepository.orElseThrow();
        GameRoom candidate = room;
        try {
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                long expectedVersion = candidate.getVersion();
                RedisGameRoomStateRepository.ConditionalWriteResult deleteResult =
                        repository.compareAndDelete(candidate.getCode(), expectedVersion);
                if (deleteResult == RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED) {
                    removeLocalRoomIfNotNewer(candidate.getCode(), expectedVersion);
                    publishStateChanged(
                            candidate.getCode(),
                            expectedVersion + 1,
                            List.of(GameRoomDelivery.lobbyExpired())
                    );
                    eventPublisher.publishEvent(new LobbyExpiredEvent(candidate.getCode()));
                    return true;
                }
                if (deleteResult == RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND) {
                    removeLocalRoomIfNotNewer(candidate.getCode(), expectedVersion);
                    return false;
                }

                Optional<GameRoom> latestRoom = repository.restore(candidate.getCode());
                if (latestRoom.isEmpty()) {
                    removeLocalRoomIfNotNewer(candidate.getCode(), expectedVersion);
                    return false;
                }
                candidate = latestRoom.orElseThrow();
                gameRegistry.replaceIfNewer(candidate);
                if (!candidate.isLobbyExpired(now)) {
                    return false;
                }
            }
        } catch (RuntimeException exception) {
            removeLocalExpiredLobby(room, now);
            eventPublisher.publishEvent(new LobbyExpiredEvent(room.getCode()));
            throw exception;
        }
        return false;
    }

    private void removeLocalExpiredLobby(GameRoom room, Instant now) {
        synchronized (room) {
            if (!isDetached(room) && room.isLobbyExpired(now)) {
                gameRegistry.removeIfSame(room);
            }
        }
    }

    public <T> T update(String code, Function<GameRoom, T> operation) {
        GameRoom room = findForUpdate(code);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            if (removeLobbyIfExpired(room, Instant.now())) {
                throw new RoomNotFoundException();
            }
            T result = operation.apply(room);
            persist(code, room, List.of());
            return result;
        }
    }

    private <T> RoomUpdate<T> updateWithDeliveriesResult(
            String code,
            Function<GameRoom, RoomUpdate<T>> operation
    ) {
        GameRoom room = findForUpdate(code);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            if (removeLobbyIfExpired(room, Instant.now())) {
                throw new RoomNotFoundException();
            }
            RoomUpdate<T> result = operation.apply(room);
            if (result.changed()) {
                if (result.deleted()) {
                    removeAttached(room, result.deliveries());
                } else {
                    persist(code, room, result.deliveries());
                }
            }
            return result;
        }
    }

    public <T> RoomUpdate<T> updateWithCas(String code, Function<GameRoom, RoomUpdate<T>> operation) {
        if (redisStateRepository.isEmpty()) {
            return updateWithDeliveriesResult(code, operation);
        }

        return updateWithRedisCas(code, operation, redisStateRepository.orElseThrow());
    }

    private <T> RoomUpdate<T> updateWithRedisCas(
            String code,
            Function<GameRoom, RoomUpdate<T>> operation,
            RedisGameRoomStateRepository repository
    ) {
        GameRoom currentRoom = findForCas(code, repository);
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            CasUpdateAttempt<T> attemptResult = attemptCasUpdate(code, currentRoom, operation, repository);
            if (attemptResult.completedUpdate().isPresent()) {
                return attemptResult.completedUpdate().orElseThrow();
            }
            currentRoom = attemptResult.currentRoom();
        }

        throw new ConcurrentGameRoomUpdateException(code, MAX_CAS_ATTEMPTS);
    }

    private <T> CasUpdateAttempt<T> attemptCasUpdate(
            String code,
            GameRoom currentRoom,
            Function<GameRoom, RoomUpdate<T>> operation,
            RedisGameRoomStateRepository repository
    ) {
        GameRoom candidate = GameRoom.restore(GameRoomState.from(currentRoom));
        long expectedVersion = candidate.getVersion();
        if (candidate.isLobbyExpired(Instant.now())) {
            return deleteExpiredLobbyOrRetry(code, currentRoom, expectedVersion, repository);
        }

        RoomUpdate<T> result = operation.apply(candidate);
        if (!result.changed()) {
            return completeUnchangedOperationOrRetry(code, expectedVersion, result, repository);
        }
        if (result.deleted()) {
            return compareAndDeleteCandidate(code, currentRoom, expectedVersion, result, repository);
        }
        return compareAndSetCandidate(code, currentRoom, candidate, expectedVersion, result, repository);
    }

    private <T> CasUpdateAttempt<T> compareAndDeleteCandidate(
            String code,
            GameRoom currentRoom,
            long expectedVersion,
            RoomUpdate<T> result,
            RedisGameRoomStateRepository repository
    ) {
        RedisGameRoomStateRepository.ConditionalWriteResult deleteResult =
                repository.compareAndDelete(code, expectedVersion);
        if (deleteResult == RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED) {
            removeLocalRoomIfNotNewer(code, expectedVersion);
            publishStateChanged(code, expectedVersion + 1, result.deliveries());
            return CasUpdateAttempt.completed(result, currentRoom);
        }
        if (deleteResult == RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND) {
            removeLocalRoomIfNotNewer(code, expectedVersion);
            throw new RoomNotFoundException();
        }
        return CasUpdateAttempt.retry(restoreLatestForCas(code, currentRoom, repository));
    }

    private <T> CasUpdateAttempt<T> deleteExpiredLobbyOrRetry(
            String code,
            GameRoom currentRoom,
            long expectedVersion,
            RedisGameRoomStateRepository repository
    ) {
        RedisGameRoomStateRepository.ConditionalWriteResult deleteResult =
                repository.compareAndDelete(code, expectedVersion);
        if (deleteResult == RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED) {
            removeLocalRoomIfNotNewer(code, expectedVersion);
            publishStateChanged(code, expectedVersion + 1, List.of(GameRoomDelivery.lobbyExpired()));
            eventPublisher.publishEvent(new LobbyExpiredEvent(code));
            throw new RoomNotFoundException();
        }
        if (deleteResult == RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND) {
            removeLocalRoomIfNotNewer(code, expectedVersion);
            throw new RoomNotFoundException();
        }
        return CasUpdateAttempt.retry(restoreLatestForCas(code, currentRoom, repository));
    }

    private <T> CasUpdateAttempt<T> completeUnchangedOperationOrRetry(
            String code,
            long expectedVersion,
            RoomUpdate<T> result,
            RedisGameRoomStateRepository repository
    ) {
        Optional<GameRoom> latestRoom = repository.restore(code);
        if (latestRoom.isEmpty()) {
            removeLocalRoomIfNotNewer(code, expectedVersion);
            throw new RoomNotFoundException();
        }

        GameRoom currentLatestRoom = latestRoom.orElseThrow();
        gameRegistry.replaceIfNewer(currentLatestRoom);
        if (currentLatestRoom.getVersion() != expectedVersion) {
            return CasUpdateAttempt.retry(currentLatestRoom);
        }
        return CasUpdateAttempt.completed(result, currentLatestRoom);
    }

    private <T> CasUpdateAttempt<T> compareAndSetCandidate(
            String code,
            GameRoom currentRoom,
            GameRoom candidate,
            long expectedVersion,
            RoomUpdate<T> result,
            RedisGameRoomStateRepository repository
    ) {
        candidate.incrementVersion();
        RedisGameRoomStateRepository.ConditionalWriteResult writeResult =
                repository.compareAndSet(candidate, expectedVersion);
        if (writeResult == RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED) {
            gameRegistry.replaceIfNewer(candidate);
            publishStateChanged(code, candidate.getVersion(), result.deliveries());
            return CasUpdateAttempt.completed(result, candidate);
        }
        if (writeResult == RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND) {
            removeLocalRoomIfNotNewer(code, expectedVersion);
            throw new RoomNotFoundException();
        }
        return CasUpdateAttempt.retry(restoreLatestForCas(code, currentRoom, repository));
    }

    private GameRoom findForCas(String code, RedisGameRoomStateRepository repository) {
        Optional<GameRoom> localRoom = gameRegistry.find(code);
        if (localRoom.isPresent()) {
            return localRoom.orElseThrow();
        }
        GameRoom restored = repository.restore(code).orElseThrow(RoomNotFoundException::new);
        return gameRegistry.replaceIfNewer(restored);
    }

    private GameRoom restoreLatestForCas(
            String code,
            GameRoom previousRoom,
            RedisGameRoomStateRepository repository
    ) {
        Optional<GameRoom> restored = repository.restore(code);
        if (restored.isEmpty()) {
            removeLocalRoomIfNotNewer(code, previousRoom.getVersion());
            throw new RoomNotFoundException();
        }
        GameRoom latestRoom = restored.orElseThrow();
        gameRegistry.replaceIfNewer(latestRoom);
        return latestRoom;
    }

    private GameRoom findForUpdate(String code) {
        return gameRegistry.find(code)
                .orElseGet(() -> restore(code).orElseThrow(RoomNotFoundException::new));
    }

    public <T> Optional<T> updateIfPresent(String code, Function<GameRoom, RoomUpdate<T>> operation) {
        Optional<GameRoom> found = gameRegistry.find(code);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        GameRoom room = found.get();
        synchronized (room) {
            if (isDetached(code, room)) {
                return Optional.empty();
            }
            if (removeLobbyIfExpired(room, Instant.now())) {
                return Optional.empty();
            }
            RoomUpdate<T> result = operation.apply(room);
            if (result.changed()) {
                persist(code, room, result.deliveries());
            }
            return Optional.ofNullable(result.value());
        }
    }

    public <T> Optional<RoomUpdate<T>> updateIfPresentWithCas(
            String code,
            Function<GameRoom, RoomUpdate<T>> operation
    ) {
        try {
            return Optional.of(updateWithCas(code, operation));
        } catch (RoomNotFoundException exception) {
            return Optional.empty();
        }
    }

    private void persist(String code, GameRoom room, List<GameRoomDelivery> deliveries) {
        redisStateRepository.ifPresent(repository -> {
            if (isDetached(code, room)) {
                return;
            }
            room.incrementVersion();
            repository.save(room);
            publishStateChanged(code, room.getVersion(), deliveries);
        });
    }

    private boolean isDetached(String code, GameRoom room) {
        return gameRegistry.find(code).orElse(null) != room;
    }

    private boolean isDetached(GameRoom room) {
        return isDetached(room.getCode(), room);
    }

    private boolean removeAttached(GameRoom room, List<GameRoomDelivery> deliveries) {
        long expectedVersion = room.getVersion();
        if (redisStateRepository.isEmpty()) {
            publishStateChanged(room.getCode(), expectedVersion + 1, deliveries);
            gameRegistry.removeIfSame(room);
            return true;
        }

        RedisGameRoomStateRepository repository = redisStateRepository.orElseThrow();
        RedisGameRoomStateRepository.ConditionalWriteResult result =
                repository.compareAndDelete(room.getCode(), expectedVersion);
        if (result == RedisGameRoomStateRepository.ConditionalWriteResult.VERSION_MISMATCH) {
            repository.restore(room.getCode()).ifPresent(gameRegistry::replaceIfNewer);
            return false;
        }
        if (result == RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND) {
            removeLocalRoomIfNotNewer(room.getCode(), expectedVersion);
            return false;
        }

        publishStateChanged(room.getCode(), expectedVersion + 1, deliveries);
        removeLocalRoomIfNotNewer(room.getCode(), expectedVersion);
        return true;
    }

    private boolean deleteExpiredRestoredLobby(GameRoom room) {
        RedisGameRoomStateRepository repository = redisStateRepository.orElseThrow();
        GameRoom candidate = room;
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            long expectedVersion = candidate.getVersion();
            RedisGameRoomStateRepository.ConditionalWriteResult result =
                    repository.compareAndDelete(candidate.getCode(), expectedVersion);
            if (result == RedisGameRoomStateRepository.ConditionalWriteResult.APPLIED) {
                publishStateChanged(
                        candidate.getCode(),
                        expectedVersion + 1,
                        List.of(GameRoomDelivery.lobbyExpired())
                );
                return true;
            }
            if (result == RedisGameRoomStateRepository.ConditionalWriteResult.KEY_NOT_FOUND) {
                return false;
            }

            Optional<GameRoom> latest = repository.restore(candidate.getCode());
            if (latest.isEmpty() || !latest.orElseThrow().isLobbyExpired(Instant.now())) {
                return false;
            }
            candidate = latest.orElseThrow();
        }
        return false;
    }

    private void publishStateChanged(String code, long version, List<GameRoomDelivery> deliveries) {
        stateChangePublisher.ifPresent(publisher -> publisher.publish(code, version, deliveries));
    }

    private record CasUpdateAttempt<T>(Optional<RoomUpdate<T>> completedUpdate, GameRoom currentRoom) {

        private static <T> CasUpdateAttempt<T> completed(RoomUpdate<T> update, GameRoom currentRoom) {
            return new CasUpdateAttempt<>(Optional.of(update), currentRoom);
        }

        private static <T> CasUpdateAttempt<T> retry(GameRoom currentRoom) {
            return new CasUpdateAttempt<>(Optional.empty(), currentRoom);
        }
    }
}
