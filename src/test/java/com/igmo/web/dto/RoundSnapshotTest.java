package com.igmo.web.dto;

import static org.assertj.core.groups.Tuple.tuple;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundSnapshotTest {

    private static final Instant PROMPT_STARTED_AT = Instant.parse("2026-07-06T10:00:00Z");
    private static final Duration PROMPT_DURATION = Duration.ofSeconds(30);
    private static final Instant GUESS_STARTED_AT = Instant.parse("2026-07-06T10:05:00Z");
    private static final Duration GUESS_DURATION = Duration.ofSeconds(60);

    @Test
    @DisplayName("현재 라운드 상태를 출제자, 이미지, 플레이어별 제출 여부로 변환한다.")
    void from_현재_라운드_상태를_변환한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String hostId = room.getPlayers().get(0).getId();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);

        // when
        RoundSnapshot snapshot = RoundSnapshot.from(room);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.roomCode()).isEqualTo("ABCD");
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.roundNumber()).isEqualTo(1);
            softly.assertThat(snapshot.totalRoundCount()).isEqualTo(3);
            softly.assertThat(snapshot.questioner().id()).isEqualTo(hostId);
            softly.assertThat(snapshot.imageUrl()).isEqualTo("https://cdn.example.com/host.png");
            softly.assertThat(snapshot.startedAt()).isEqualTo(GUESS_STARTED_AT);
            softly.assertThat(snapshot.guessDeadline()).isEqualTo(GUESS_STARTED_AT.plus(GUESS_DURATION));
            softly.assertThat(snapshot.guessEntries())
                    .extracting(entry -> entry.player().id(), GuessEntryView::submitted)
                    .containsExactly(
                            tuple(guest1Id, true),
                            tuple(guest2Id, false)
                    );
        });
    }

    @Test
    @DisplayName("라운드 스냅샷 메시지는 ROUND_SNAPSHOT 타입으로 감싼다.")
    void roundSnapshot_메시지_타입을_지정한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        RoundSnapshot snapshot = RoundSnapshot.from(room);

        // when
        RoomMessage<RoundSnapshot> message = RoomMessage.roundSnapshot(snapshot);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(message.type()).isEqualTo(RoomMessageType.ROUND_SNAPSHOT);
            softly.assertThat(message.payload()).isSameAs(snapshot);
        });
    }

    private GameRoom createRoomInGuessing() throws Exception {
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");
        room.completeImageGeneration(guest2.getId(), "https://cdn.example.com/guest-2.png");
        setPhase(room, GamePhase.PLAYING);
        room.startRounds(GUESS_STARTED_AT, GUESS_DURATION);
        return room;
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        Field field = GameRoom.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(room, phase);
    }
}
