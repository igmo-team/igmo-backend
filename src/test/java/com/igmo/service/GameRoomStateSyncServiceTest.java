package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.RoomMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameRoomStateSyncServiceTest {

    private final GameRegistry gameRegistry = new GameRegistry();
    private final GameEventPublisher gameEventPublisher = mock(GameEventPublisher.class);
    private final GameRoomStateSyncService service = new GameRoomStateSyncService(
            new GameRoomRepository(gameRegistry),
            gameEventPublisher);

    @Test
    @DisplayName("방 상태 동기화 요청 시 복원한 로비 스냅샷을 요청 플레이어 개인큐로 전달한다.")
    void sync_복원한_로비_스냅샷을_요청자에게_전달한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        gameRegistry.saveIfAbsent(room);
        String playerId = room.getPlayers().getFirst().getId();

        // when
        service.sync("ABCD", playerId);

        // then
        ArgumentCaptor<RoomMessage<?>> snapshotCaptor = ArgumentCaptor.forClass(RoomMessage.class);
        verify(gameEventPublisher).sendRoomState(eq(playerId), eq("ABCD"), snapshotCaptor.capture());
        RoomMessage<?> snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.type()).isEqualTo(com.igmo.web.dto.RoomMessageType.LOBBY_SNAPSHOT);
        assertThat(snapshot.payload()).isInstanceOf(LobbySnapshot.class);
    }

    @Test
    @DisplayName("존재하지 않는 방을 동기화하면 RoomNotFoundException을 던진다.")
    void sync_존재하지_않는_방이면_예외를_던진다() {
        // when // then
        assertThatThrownBy(() -> service.sync("ABCD", "player-1"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어가 동기화를 요청하면 PlayerNotFoundException을 던진다.")
    void sync_방에_없는_플레이어면_예외를_던진다() {
        // given
        gameRegistry.saveIfAbsent(GameRoom.create("ABCD", new Player("호스트")));

        // when // then
        assertThatThrownBy(() -> service.sync("ABCD", "unknown-player"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }
}
