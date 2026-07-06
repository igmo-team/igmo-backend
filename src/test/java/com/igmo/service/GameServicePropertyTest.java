package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.igmo.web.dto.CreateGameResponse;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "igmo.game.disconnect-grace=17s")
class GameServicePropertyTest {

    @Autowired
    private GameService gameService;

    @MockitoBean(name = "disconnectGraceScheduler")
    private TaskScheduler disconnectGraceScheduler;

    @Test
    @DisplayName("연결 끊김 유예 시간은 igmo.game.disconnect-grace 프로퍼티 값을 사용한다.")
    void handleDisconnect_프로퍼티의_유예_시간을_사용한다() {
        // given
        ScheduledFuture<?> scheduledRemoval = mock(ScheduledFuture.class);
        given(disconnectGraceScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledRemoval);
        CreateGameResponse created = gameService.createGame("호스트");
        Instant before = Instant.now();

        // when
        gameService.handleDisconnect(created.roomCode(), created.playerId());

        // then
        Instant after = Instant.now();
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(disconnectGraceScheduler).schedule(any(Runnable.class), scheduledAt.capture());
        assertThat(scheduledAt.getValue())
                .isBetween(before.plusSeconds(17), after.plusSeconds(17));
    }
}
