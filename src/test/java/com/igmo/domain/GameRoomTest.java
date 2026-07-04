package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
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

    @Test
    @DisplayName("참가자가 방을 나가면 목록에서 제거되고 방장은 유지된다.")
    void removePlayer_참가자가_나가면_목록에서_제거되고_방장은_유지된다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest = new Player("참가자");
        room.addPlayer(guest);

        // when
        boolean removed = room.removePlayer(guest.getId());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isTrue();
            softly.assertThat(room.getPlayers()).containsExactly(host);
            softly.assertThat(room.getHostId()).isEqualTo(host.getId());
        });
    }

    @Test
    @DisplayName("방장이 방을 나가면 남은 참가자 중에서 새 방장이 선정된다.")
    void removePlayer_방장이_나가면_남은_참가자_중에서_새_방장이_선정된다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);

        // when
        boolean removed = room.removePlayer(host.getId());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isTrue();
            softly.assertThat(room.getPlayers()).containsExactly(guest1, guest2);
            softly.assertThat(room.getHostId()).isIn(guest1.getId(), guest2.getId());
        });
    }

    @Test
    @DisplayName("마지막 참가자가 나가면 방이 빈 상태가 된다.")
    void removePlayer_마지막_참가자가_나가면_방이_빈_상태가_된다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);

        // when
        boolean removed = room.removePlayer(host.getId());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isTrue();
            softly.assertThat(room.isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("방에 없는 플레이어를 제거하면 false를 반환하고 목록은 변하지 않는다.")
    void removePlayer_방에_없는_플레이어면_false를_반환하고_목록은_그대로다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);

        // when
        boolean removed = room.removePlayer("unknown-player-id");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isFalse();
            softly.assertThat(room.getPlayers()).containsExactly(host);
            softly.assertThat(room.getHostId()).isEqualTo(host.getId());
        });
    }

    @Test
    @DisplayName("참가자의 준비 상태를 변경하면 해당 참가자에게 반영된다.")
    void changePlayerReady_준비_상태를_변경하면_반영된다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        Player guest = new Player("참가자");
        room.addPlayer(guest);

        // when
        room.changePlayerReady(guest.getId(), true);

        // then
        Player found = room.getPlayers().stream()
                .filter(player -> player.getId().equals(guest.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(found.isReady()).isTrue();
    }

    @Test
    @DisplayName("로비 단계가 아니면 준비 상태를 변경할 때 GameAlreadyStartedException을 던진다.")
    void changePlayerReady_이미_시작된_게임이면_예외를_던진다() throws Exception {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        setPhase(room, GamePhase.GENERATING);

        // when & then
        assertThatThrownBy(() -> room.changePlayerReady(host.getId(), true))
                .isInstanceOf(GameAlreadyStartedException.class)
                .hasMessage("이미 시작된 게임입니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어의 준비 상태 변경은 예외 없이 무시한다.")
    void changePlayerReady_방에_없는_플레이어면_무시한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);

        // when & then
        assertThatCode(() -> room.changePlayerReady("unknown-player-id", true))
                .doesNotThrowAnyException();
        assertThat(host.isReady()).isFalse();
    }

    @Test
    @DisplayName("방장이 시작하면 방장 외 모든 참가자가 준비되고 3명 이상일 때 GENERATING 단계로 진행한다.")
    void start_방장이_조건을_충족하면_다음_단계로_진행한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);

        // when
        room.start(host.getId());

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.GENERATING);
    }

    @Test
    @DisplayName("방장이 아닌 참가자가 시작하면 NotHostException을 던진다.")
    void start_방장이_아니면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);

        // when & then
        assertThatThrownBy(() -> room.start(guest1.getId()))
                .isInstanceOf(NotHostException.class)
                .hasMessage("방장만 게임을 시작할 수 있습니다.");
    }

    @Test
    @DisplayName("참가자가 3명 미만이면 시작할 때 InsufficientPlayersException을 던진다.")
    void start_참가자가_3명_미만이면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest = new Player("참가자");
        room.addPlayer(guest);
        room.changePlayerReady(guest.getId(), true);

        // when & then
        assertThatThrownBy(() -> room.start(host.getId()))
                .isInstanceOf(InsufficientPlayersException.class)
                .hasMessage("게임을 시작하려면 최소 3명이 필요합니다.");
    }

    @Test
    @DisplayName("방장 외 준비하지 않은 참가자가 있으면 시작할 때 PlayersNotReadyException을 던진다.")
    void start_준비하지_않은_참가자가_있으면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);

        // when & then
        assertThatThrownBy(() -> room.start(host.getId()))
                .isInstanceOf(PlayersNotReadyException.class)
                .hasMessage("모든 참가자가 준비되지 않았습니다.");
    }

    @Test
    @DisplayName("이미 시작된 게임을 다시 시작하면 GameAlreadyStartedException을 던진다.")
    void start_이미_시작된_게임이면_예외를_던진다() throws Exception {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        room.addPlayer(new Player("참가자1"));
        room.addPlayer(new Player("참가자2"));
        setPhase(room, GamePhase.GENERATING);

        // when & then
        assertThatThrownBy(() -> room.start(host.getId()))
                .isInstanceOf(GameAlreadyStartedException.class)
                .hasMessage("이미 시작된 게임입니다.");
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        Field field = GameRoom.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(room, phase);
    }
}
