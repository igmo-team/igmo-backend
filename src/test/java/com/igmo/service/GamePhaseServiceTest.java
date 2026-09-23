package com.igmo.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GameRoom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GamePhaseServiceTest extends GamePhaseServiceTestSupport {

    @Test
    @DisplayName("Redis에서 복원한 투표 방의 투표 마감 작업을 현재 인스턴스에 등록한다.")
    void restorePhaseExpiration_투표방의_마감작업을등록한다() {
        // given
        setUpRoomInVoting();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        clearInvocations(gamePhaseDeadlineScheduler);

        // when
        gamePhaseService.restorePhaseExpiration(new GameRoomRestoredEvent(room));

        // then
        verify(gamePhaseDeadlineScheduler).schedule(any(Runnable.class), eq(room.getVoteDeadline()));
    }
}
