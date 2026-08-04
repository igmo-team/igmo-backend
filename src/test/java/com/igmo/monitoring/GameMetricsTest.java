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

    @Test
    void 메시지타입_전송경로_결과별로_전송_메트릭을_기록한다() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GameMetrics gameMetrics = new GameMetrics(meterRegistry, new GameRegistry());

        gameMetrics.recordWebSocketMessageSend(
                WebSocketMessageType.ROUND_SNAPSHOT,
                WebSocketChannelType.ROOM_TOPIC,
                WebSocketMessageOutcome.SUCCESS);
        gameMetrics.recordWebSocketMessageSend(
                WebSocketMessageType.GUESS_SUBMISSION_RESULT,
                WebSocketChannelType.PRIVATE_QUEUE,
                WebSocketMessageOutcome.FAILURE);

        assertThat(meterRegistry.get("websocket.message.send")
                .tags(
                        "messageType", "ROUND_SNAPSHOT",
                        "channelType", "ROOM_TOPIC",
                        "outcome", "SUCCESS")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("websocket.message.send")
                .tags(
                        "messageType", "GUESS_SUBMISSION_RESULT",
                        "channelType", "PRIVATE_QUEUE",
                        "outcome", "FAILURE")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
