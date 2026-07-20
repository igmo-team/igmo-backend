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

class RoundResultSnapshotTest {

    private static final Instant PROMPT_STARTED_AT = Instant.parse("2026-07-06T10:00:00Z");
    private static final Duration PROMPT_DURATION = Duration.ofSeconds(30);
    private static final Instant GUESS_STARTED_AT = Instant.parse("2026-07-06T10:05:00Z");
    private static final Duration GUESS_DURATION = Duration.ofSeconds(60);
    private static final Instant VOTING_OPENED_AT = Instant.parse("2026-07-06T10:05:10Z");
    private static final Duration VOTE_DURATION = Duration.ofSeconds(30);
    private static final Instant RESULTS_OPENED_AT = Instant.parse("2026-07-06T10:05:40Z");
    private static final Duration RESULT_DURATION = Duration.ofSeconds(15);

    @Test
    @DisplayName("결과 스냅샷은 정답과 각 추측의 작성자, 득표수, 라운드 점수, 누적 점수를 공개한다.")
    void from_결과를_공개한다() throws Exception {
        // given
        GameRoom room = createRoomInResults();
        String hostId = room.getPlayers().get(0).getId();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();

        // when
        RoundResultSnapshot snapshot = RoundResultSnapshot.from(room);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.roomCode()).isEqualTo("ABCD");
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.RESULTS);
            softly.assertThat(snapshot.roundNumber()).isEqualTo(1);
            softly.assertThat(snapshot.totalRoundCount()).isEqualTo(3);
            softly.assertThat(snapshot.questioner().id()).isEqualTo(hostId);
            softly.assertThat(snapshot.answerText()).isEqualTo("호스트 프롬프트");
            softly.assertThat(snapshot.resultDeadline()).isEqualTo(RESULTS_OPENED_AT.plus(RESULT_DURATION));
            softly.assertThat(snapshot.results())
                    .extracting(view -> view.player().id(), RoundResultView::isAnswer,
                            RoundResultView::voteCount, RoundResultView::roundScore)
                    .containsExactlyInAnyOrder(
                            tuple(hostId, true, 1, 2),
                            tuple(guest1Id, false, 1, 3),
                            tuple(guest2Id, false, 0, 0)
                    );
            softly.assertThat(snapshot.results())
                    .filteredOn(RoundResultView::isAnswer)
                    .extracting(RoundResultView::guessText)
                    .containsExactly("호스트 프롬프트");
            softly.assertThat(snapshot.players())
                    .extracting(PlayerView::id, PlayerView::score)
                    .containsExactly(
                            tuple(hostId, 2),
                            tuple(guest1Id, 3),
                            tuple(guest2Id, 0)
                    );
        });
    }

    @Test
    @DisplayName("결과 스냅샷 메시지는 ROUND_RESULT_SNAPSHOT 타입으로 감싼다.")
    void roundResultSnapshot_메시지_타입을_지정한다() throws Exception {
        // given
        GameRoom room = createRoomInResults();
        RoundResultSnapshot snapshot = RoundResultSnapshot.from(room);

        // when
        RoomMessage<RoundResultSnapshot> message = RoomMessage.roundResultSnapshot(snapshot);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(message.type()).isEqualTo(RoomMessageType.ROUND_RESULT_SNAPSHOT);
            softly.assertThat(message.payload()).isSameAs(snapshot);
        });
    }

    // guest1: 정답(+2) + guest2에게 낚임(+1)=3, host(출제자): 정답자 1명(+2)=2, guest2: 오답=0
    private GameRoom createRoomInResults() throws Exception {
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
        room.submitGuess(guest1.getId(), "강아지가 기타를 치는 장면", GUESS_STARTED_AT);
        room.submitGuess(guest2.getId(), "고양이가 드럼을 치는 장면", GUESS_STARTED_AT);
        room.completeGuessSubmission(VOTING_OPENED_AT, VOTE_DURATION);
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        String guess1OptionId = room.getCurrentRound().getGuesses().get(0).getGuessId();
        room.submitVote(guest1.getId(), answerOptionId, VOTING_OPENED_AT.plusSeconds(1));
        room.submitVote(guest2.getId(), guess1OptionId, VOTING_OPENED_AT.plusSeconds(2));
        room.completeVoting(RESULTS_OPENED_AT, RESULT_DURATION);
        return room;
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        Field field = GameRoom.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(room, phase);
    }
}
