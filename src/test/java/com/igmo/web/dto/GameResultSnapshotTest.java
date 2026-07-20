package com.igmo.web.dto;

import static org.assertj.core.groups.Tuple.tuple;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import java.lang.reflect.Field;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameResultSnapshotTest {

    @Test
    @DisplayName("최종 순위는 누적 점수 내림차순으로 순위를 매긴다.")
    void from_점수_내림차순으로_순위를_매긴다() throws Exception {
        // given
        GameRoom room = createRoomWithScores(5, 3, 1);
        List<Player> players = room.getPlayers();

        // when
        GameResultSnapshot snapshot = GameResultSnapshot.from(room);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.roomCode()).isEqualTo("ABCD");
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.ENDED);
            softly.assertThat(snapshot.finalRanking())
                    .extracting(view -> view.player().id(), PlayerRankingView::rank, PlayerRankingView::totalScore)
                    .containsExactly(
                            tuple(players.get(0).getId(), 1, 5),
                            tuple(players.get(1).getId(), 2, 3),
                            tuple(players.get(2).getId(), 3, 1)
                    );
        });
    }

    @Test
    @DisplayName("점수가 같으면 같은 순위를 주고 다음 순위는 인원수만큼 건너뛴다.")
    void from_동점자는_같은_순위를_받는다() throws Exception {
        // given
        GameRoom room = createRoomWithScores(3, 3, 1);
        List<Player> players = room.getPlayers();

        // when
        GameResultSnapshot snapshot = GameResultSnapshot.from(room);

        // then
        SoftAssertions.assertSoftly(softly ->
                softly.assertThat(snapshot.finalRanking())
                        .extracting(view -> view.player().id(), PlayerRankingView::rank)
                        .containsExactly(
                                tuple(players.get(0).getId(), 1),
                                tuple(players.get(1).getId(), 1),
                                tuple(players.get(2).getId(), 3)
                        ));
    }

    @Test
    @DisplayName("게임 종료 스냅샷 메시지는 GAME_RESULT_SNAPSHOT 타입으로 감싼다.")
    void gameResultSnapshot_메시지_타입을_지정한다() throws Exception {
        // given
        GameRoom room = createRoomWithScores(1, 1, 1);
        GameResultSnapshot snapshot = GameResultSnapshot.from(room);

        // when
        RoomMessage<GameResultSnapshot> message = RoomMessage.gameResultSnapshot(snapshot);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(message.type()).isEqualTo(RoomMessageType.GAME_RESULT_SNAPSHOT);
            softly.assertThat(message.payload()).isSameAs(snapshot);
        });
    }

    private GameRoom createRoomWithScores(int hostScore, int guest1Score, int guest2Score) throws Exception {
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        host.addScore(hostScore);
        guest1.addScore(guest1Score);
        guest2.addScore(guest2Score);
        setPhase(room, GamePhase.ENDED);
        return room;
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        Field field = GameRoom.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(room, phase);
    }
}
