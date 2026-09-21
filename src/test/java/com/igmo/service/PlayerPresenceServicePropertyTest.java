package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.igmo.store.GameRegistry;
import com.igmo.support.AbstractNonWebSpringBootTest;
import com.igmo.web.dto.CreateGameResponse;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
        "igmo.game.disconnect-grace=17s",
        "igmo.game.lobby-duration=23s"
})
class PlayerPresenceServicePropertyTest extends AbstractNonWebSpringBootTest {

    @Autowired
    private GameLobbyService gameLobbyService;

    @Autowired
    private PlayerPresenceService playerPresenceService;

    @Autowired
    private PlayerSessionRegistry playerSessionRegistry;

    @Autowired
    private GameRegistry gameRegistry;

    @MockitoBean(name = "disconnectGraceScheduler")
    private TaskScheduler disconnectGraceScheduler;

    @MockitoBean(name = "gamePhaseDeadlineScheduler")
    private TaskScheduler gamePhaseDeadlineScheduler;

    @MockitoBean(name = "imageGenerationCompletionScheduler")
    private TaskScheduler imageGenerationCompletionScheduler;

    @AfterEach
    void 테스트_게임방을_정리한다() {
        gameRegistry.snapshot().forEach(room -> gameRegistry.remove(room.getCode()));
    }

    @Test
    @DisplayName("연결 끊김 유예 시간은 igmo.game.disconnect-grace 프로퍼티 값을 사용한다.")
    void handleDisconnect_프로퍼티의_유예_시간을_사용한다() {
        // given
        given(gamePhaseDeadlineScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willReturn(mock(ScheduledFuture.class));
        ScheduledFuture<?> scheduledRemoval = mock(ScheduledFuture.class);
        given(disconnectGraceScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledRemoval);
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        playerSessionRegistry.register(new PlayerKey(created.roomCode(), created.playerId()), "session-1");
        Instant before = Instant.now();

        // when
        playerPresenceService.handleDisconnect(created.roomCode(), created.playerId(), "session-1");

        // then
        Instant after = Instant.now();
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(disconnectGraceScheduler).schedule(any(Runnable.class), scheduledAt.capture());
        assertThat(scheduledAt.getValue())
                .isBetween(before.plusSeconds(17), after.plusSeconds(17));
    }

    @Test
    @DisplayName("로비 대기 시간은 igmo.game.lobby-duration 프로퍼티 값을 사용한다.")
    void createGame_프로퍼티의_로비_대기_시간을_사용한다() {
        // given
        given(gamePhaseDeadlineScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willReturn(mock(ScheduledFuture.class));
        Instant before = Instant.now();

        // when
        gameLobbyService.createGame("호스트");

        // then
        Instant after = Instant.now();
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(gamePhaseDeadlineScheduler).schedule(any(Runnable.class), scheduledAt.capture());
        assertThat(scheduledAt.getValue())
                .isBetween(before.plusSeconds(23), after.plusSeconds(23));
    }
}
