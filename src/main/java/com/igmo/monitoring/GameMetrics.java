package com.igmo.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class GameMetrics {

    private final Timer imageGenerationDuration;

    public GameMetrics(MeterRegistry meterRegistry) {
        imageGenerationDuration = meterRegistry.timer("image.generation.duration");
    }

    public void recordImageGenerationDuration(Duration duration) {
        imageGenerationDuration.record(duration);
    }
}
