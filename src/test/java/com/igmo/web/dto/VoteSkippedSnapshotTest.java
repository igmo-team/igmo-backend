package com.igmo.web.dto;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoteSkippedSnapshotTest {

    private static final Instant PROMPT_STARTED_AT = Instant.parse("2026-07-06T10:00:00Z");
    private static final Instant GUESS_STARTED_AT = Instant.parse("2026-07-06T10:05:00Z");
    private static final Instant SKIPPED_STARTED_AT = Instant.parse("2026-07-06T10:05:10Z");
    private static final Duration VOTE_SKIPPED_DURATION = Duration.ofSeconds(3);

    @Test
    @DisplayName("투표 생략 상태를 방과 라운드, 시작·마감 시각, 사유로 변환한다.")
    void from_투표생략_상태를_변환한다() throws Exception {
        GameRoom room = createRoomWithSkippedVote();

        VoteSkippedSnapshot snapshot = VoteSkippedSnapshot.from(room);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.roomCode()).isEqualTo("ABCD");
            softly.assertThat(snapshot.roundNumber()).isEqualTo(1);
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.VOTE_SKIPPED);
            softly.assertThat(snapshot.startedAt()).isEqualTo(SKIPPED_STARTED_AT);
            softly.assertThat(snapshot.deadline()).isEqualTo(SKIPPED_STARTED_AT.plus(VOTE_SKIPPED_DURATION));
            softly.assertThat(snapshot.reason()).isEqualTo(VoteSkippedReason.ALL_PERFECT);
        });
    }

    @Test
    @DisplayName("투표 생략 스냅샷 메시지는 VOTE_SKIPPED_SNAPSHOT 타입으로 감싼다.")
    void voteSkippedSnapshot_메시지_타입을_지정한다() throws Exception {
        VoteSkippedSnapshot snapshot = VoteSkippedSnapshot.from(createRoomWithSkippedVote());

        RoomMessage<VoteSkippedSnapshot> message = RoomMessage.voteSkippedSnapshot(snapshot);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(message.type()).isEqualTo(RoomMessageType.VOTE_SKIPPED_SNAPSHOT);
            softly.assertThat(message.payload()).isSameAs(snapshot);
        });
    }

    private GameRoom createRoomWithSkippedVote() throws Exception {
        Player host = new Player("호스트");
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        GameRoom room = GameRoom.create("ABCD", host);
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, Duration.ofSeconds(30));
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");
        room.completeImageGeneration(guest2.getId(), "https://cdn.example.com/guest-2.png");
        setPhase(room, GamePhase.PLAYING);
        room.startRounds(GUESS_STARTED_AT, Duration.ofSeconds(60));
        submitPerfectAndFakeGuess(room, guest1);
        submitPerfectAndFakeGuess(room, guest2);
        room.completeGuessSubmission(SKIPPED_STARTED_AT, Duration.ofSeconds(30), VOTE_SKIPPED_DURATION);
        return room;
    }

    private void submitPerfectAndFakeGuess(GameRoom room, Player player) {
        room.submitGuess(player.getId(), "호스트프롬프트", GUESS_STARTED_AT);
        room.submitGuess(player.getId(), player.getNickname().value() + " 가짜 추측", GUESS_STARTED_AT.plusSeconds(1));
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        Field field = GameRoom.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(room, phase);
    }
}
