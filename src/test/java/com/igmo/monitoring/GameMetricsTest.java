package com.igmo.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.igmo.domain.GameRoom;
import com.igmo.store.GameRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GameMetricsTest {

    @Test
    void 활성_게임_방_수를_측정한다() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom gameRoom = mock(GameRoom.class);
        when(gameRoom.getCode()).thenReturn("ABCD");
        gameRegistry.saveIfAbsent(gameRoom);

        new GameMetrics(meterRegistry, gameRegistry);

        assertThat(meterRegistry.get("game.room.active").gauge().value()).isEqualTo(1.0);
    }
}
