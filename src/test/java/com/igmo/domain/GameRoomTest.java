package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
            softly.assertThat(room.isInLobby()).isTrue();
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
    @DisplayName("정원(8명)이 모두 차면 isFull은 참이다.")
    void isFull_정원이_차면_참을_반환한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        for (int i = 1; i <= 7; i++) {
            room.addPlayer(new Player("참가자" + i));
        }

        // when
        boolean full = room.isFull();

        // then
        assertThat(full).isTrue();
    }

    @Test
    @DisplayName("정원 미만이면 isFull은 거짓이다.")
    void isFull_정원_미만이면_거짓을_반환한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        for (int i = 1; i <= 6; i++) {
            room.addPlayer(new Player("참가자" + i));
        }

        // when
        boolean full = room.isFull();

        // then
        assertThat(full).isFalse();
    }

    @Test
    @DisplayName("같은 닉네임의 참가자가 있으면 hasNickname은 참이다.")
    void hasNickname_같은_닉네임이_있으면_참을_반환한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        room.addPlayer(new Player("참가자"));

        // when & then
        assertThat(room.hasNickname("참가자")).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 닉네임이면 hasNickname은 거짓이다.")
    void hasNickname_없는_닉네임이면_거짓을_반환한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));

        // when & then
        assertThat(room.hasNickname("없는닉네임")).isFalse();
    }
}
