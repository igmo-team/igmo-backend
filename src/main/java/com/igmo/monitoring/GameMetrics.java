package com.igmo.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class GameMetrics {

    private final Timer imageGenerationDuration;
    private final Counter imageGenerationFailure;
    private final Timer imageUploadDuration;
    private final Counter imageUploadFailure;
    private final Counter websocketBroadcastCount;
    private final Counter websocketBroadcastFailure;

    public GameMetrics(MeterRegistry meterRegistry) {
        imageGenerationDuration = meterRegistry.timer("image.generation.duration");
        imageGenerationFailure = meterRegistry.counter("image.generation.failure");
        imageUploadDuration = meterRegistry.timer("image.upload.duration");
        imageUploadFailure = meterRegistry.counter("image.upload.failure");
        websocketBroadcastCount = meterRegistry.counter("websocket.broadcast.count");
        websocketBroadcastFailure = meterRegistry.counter("websocket.broadcast.failure");
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

    public void incrementWebsocketBroadcastCount() {
        websocketBroadcastCount.increment();
    }

    public void incrementWebsocketBroadcastFailure() {
        websocketBroadcastFailure.increment();
    }
}
