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
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import java.time.Duration;
import java.time.Instant;
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
    @DisplayName("투표 상태를 동기화하면 공개 스냅샷과 요청자의 개인 투표 상태를 함께 전달한다.")
    void sync_투표상태와_요청자개인투표상태를_함께_전달한다() {
        // given
        GameRoom room = createVotingRoom();
        gameRegistry.saveIfAbsent(room);
        String playerId = room.getPlayers().get(1).getId();
        OwnVoteOptionNotice expectedNotice = OwnVoteOptionNotice.of(
                room.getCode(),
                room.getCurrentRound().getRoundNumber(),
                room.getCurrentRoundOwnVoteOptions().get(playerId)
        );

        // when
        service.sync(room.getCode(), playerId);

        // then
        ArgumentCaptor<RoomMessage<?>> snapshotCaptor = ArgumentCaptor.forClass(RoomMessage.class);
        ArgumentCaptor<OwnVoteOptionNotice> noticeCaptor = ArgumentCaptor.forClass(OwnVoteOptionNotice.class);
        verify(gameEventPublisher).sendRoomState(eq(playerId), eq(room.getCode()), snapshotCaptor.capture());
        verify(gameEventPublisher).sendOwnVoteOption(eq(playerId), noticeCaptor.capture());
        assertThat(snapshotCaptor.getValue().type()).isEqualTo(RoomMessageType.VOTE_SNAPSHOT);
        assertThat(noticeCaptor.getValue()).isEqualTo(expectedNotice);
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

    private GameRoom createVotingRoom() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(room.getHostId(), base, Duration.ofSeconds(30));
        for (Player player : room.getPlayers()) {
            room.submitPrompt(player.getId(), "프롬프트-" + player.getNickname().value(), base);
            room.completeImageGeneration(player.getId(), "https://example.com/" + player.getId() + ".png");
        }
        room.advanceToPlaying();
        room.startRounds(base.plusSeconds(1), Duration.ofSeconds(30));
        room.submitGuess(guest1.getId(), "추측-참가자1", base.plusSeconds(2));
        room.submitGuess(guest2.getId(), "추측-참가자2", base.plusSeconds(2));
        room.completeGuessSubmission(base.plusSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));
        return room;
    }
}
