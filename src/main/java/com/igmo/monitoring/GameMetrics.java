package com.igmo.monitoring;

import com.igmo.store.GameRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class GameMetrics {

    private final Timer imageGenerationDuration;
    private final Counter imageGenerationFailure;
    private final Timer imageUploadDuration;
    private final Counter imageUploadFailure;
    private final MeterRegistry meterRegistry;
    private final Set<String> activeWebSocketSessionIds = ConcurrentHashMap.newKeySet();

    public GameMetrics(MeterRegistry meterRegistry, GameRegistry gameRegistry) {
        this.meterRegistry = meterRegistry;
        imageGenerationDuration = meterRegistry.timer("image.generation.duration");
        imageGenerationFailure = meterRegistry.counter("image.generation.failure");
        imageUploadDuration = meterRegistry.timer("image.upload.duration");
        imageUploadFailure = meterRegistry.counter("image.upload.failure");
        meterRegistry.gauge("websocket.connection.active", activeWebSocketSessionIds, Set::size);
        meterRegistry.gauge("game.room.active", gameRegistry, GameRegistry::count);
    }

    public void recordImageGenerationDuration(Duration duration) {
        imageGenerationDuration.record(duration);
    }

    public void incrementImageGenerationFailure() {
        imageGenerationFailure.increment();
    }

    public void recordImageUploadDuration(Duration duration) {
        imageUploadDuration.record(duration);
    }

    public void incrementImageUploadFailure() {
        imageUploadFailure.increment();
    }

    public void recordWebSocketMessageSend(
            WebSocketMessageType messageType,
            WebSocketChannelType channelType,
            WebSocketMessageOutcome outcome
    ) {
        try {
            meterRegistry.counter(
                    "websocket.message.send",
                    "messageType", messageType.name(),
                    "channelType", channelType.name(),
                    "outcome", outcome.name()
            ).increment();
        } catch (RuntimeException ignored) {
        }
    }

    public void connectWebSocket(String sessionId) {
        activeWebSocketSessionIds.add(sessionId);
    }

    public void disconnectWebSocket(String sessionId) {
        activeWebSocketSessionIds.remove(sessionId);
    }
}
