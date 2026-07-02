package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.RoomFullException;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameRoomTest {

    @Test
    @DisplayName("방을 생성하면 호스트가 첫 참가자로 등록되고 LOBBY 상태가 된다.")
    void create_방을_생성하면_호스트가_첫_참가자이고_로비_상태다() {
        // given
        Player host = new Player("호스트");

        // when
        GameRoom room = GameRoom.create("ABCD", host);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getCode()).isEqualTo("ABCD");
            softly.assertThat(room.getHostId()).isEqualTo(host.getId());
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.LOBBY);
            softly.assertThat(room.getPlayers()).containsExactly(host);
        });
    }

    @Test
    @DisplayName("참가자를 추가하면 목록에 포함되고 참가자 id를 반환한다.")
    void addPlayer_참가자를_추가하면_목록에_포함되고_id를_반환한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        Player guest = new Player("참가자");

        // when
        String playerId = room.addPlayer(guest);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(playerId).isEqualTo(guest.getId());
            softly.assertThat(room.getPlayers()).contains(guest);
            softly.assertThat(room.getPlayers()).hasSize(2);
        });
    }

    @Test
    @DisplayName("정원(8명)이 가득 찬 방에 참가자를 추가하면 RoomFullException을 던진다.")
    void addPlayer_정원이_가득_차면_예외를_던진다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        for (int i = 1; i <= 7; i++) {
            room.addPlayer(new Player("참가자" + i));
        }

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("초과")))
                .isInstanceOf(RoomFullException.class)
                .hasMessage("방 정원이 가득 찼습니다.");
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임으로 참가자를 추가하면 DuplicateNicknameException을 던진다.")
    void addPlayer_닉네임이_중복되면_예외를_던진다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        room.addPlayer(new Player("참가자"));

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("참가자")))
                .isInstanceOf(DuplicateNicknameException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    @DisplayName("앞뒤 공백만 다른 닉네임으로 참가하면 DuplicateNicknameException을 던진다.")
    void addPlayer_공백만_다른_닉네임은_중복으로_처리한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        room.addPlayer(new Player("참가자"));

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("  참가자  ")))
                .isInstanceOf(DuplicateNicknameException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    @DisplayName("로비 단계가 아닌 방에 참가자를 추가하면 GameAlreadyStartedException을 던진다.")
    void addPlayer_이미_시작된_게임이면_예외를_던진다() throws Exception {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        setPhase(room, GamePhase.GENERATING);

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("참가자")))
                .isInstanceOf(GameAlreadyStartedException.class)
                .hasMessage("이미 시작된 게임입니다.");
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        Field field = GameRoom.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(room, phase);
    }
}
