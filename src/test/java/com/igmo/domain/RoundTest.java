package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.DuplicateVoteException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessMatchesAnswerException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.InvalidVoteOptionException;
import com.igmo.domain.exception.SelfVoteNotAllowedException;
import com.igmo.domain.exception.VoteNotAllowedException;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-19T12:00:00Z");

    @Test
    @DisplayName("출제자가 아닌 플레이어가 추측을 제출하면 제출 순서대로 저장한다.")
    void submitGuess_출제자가_아니면_추측을_저장한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");

        // when
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT.plusSeconds(1));

        // then
        List<GuessEntry> guesses = round.getGuesses();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guesses).hasSize(2);
            softly.assertThat(guesses.get(0).getPlayerId()).isEqualTo("guesser-1");
            softly.assertThat(guesses.get(0).getGuess()).isEqualTo("강아지가 기타를 치는 장면");
            softly.assertThat(guesses.get(0).getSubmittedAt()).isEqualTo(SUBMITTED_AT);
            softly.assertThat(guesses.get(0).getGuessId()).isNotBlank();
            softly.assertThat(guesses.get(1).getPlayerId()).isEqualTo("guesser-2");
        });
    }

    @Test
    @DisplayName("출제자가 추측을 제출하면 GuessNotAllowedException을 던진다.")
    void submitGuess_출제자가_제출하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");

        // when & then
        assertThatThrownBy(() -> round.submitGuess("questioner", "강아지가 기타를 치는 장면", SUBMITTED_AT))
                .isInstanceOf(GuessNotAllowedException.class)
                .hasMessage("출제자는 추측을 제출할 수 없습니다.");
    }

    @Test
    @DisplayName("같은 플레이어가 추측을 두 번 제출하면 DuplicateGuessSubmissionException을 던진다.")
    void submitGuess_중복_제출하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);

        // when & then
        assertThatThrownBy(() -> round.submitGuess("guesser-1", "고양이가 드럼을 치는 장면", SUBMITTED_AT))
                .isInstanceOf(DuplicateGuessSubmissionException.class)
                .hasMessage("이미 추측을 제출했습니다.");
    }

    @Test
    @DisplayName("정답 프롬프트와 정규화 후 동일한 추측은 GuessMatchesAnswerException을 던진다.")
    void submitGuess_정답과_동일하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");

        // when & then
        assertThatThrownBy(() -> round.submitGuess("guesser-1", "  고양이가   피아노를 치는   장면 ", SUBMITTED_AT))
                .isInstanceOf(GuessMatchesAnswerException.class)
                .hasMessage("정답 프롬프트와 동일한 추측은 제출할 수 없습니다.");
    }

    @Test
    @DisplayName("영문 대소문자만 다른 추측도 정답과 동일한 것으로 판정한다.")
    void submitGuess_대소문자만_다른_정답도_동일로_판정한다() {
        // given
        Round round = createRound("questioner", "AI Robot이 그린 그림");

        // when & then
        assertThatThrownBy(() -> round.submitGuess("guesser-1", "ai robot이 그린 그림", SUBMITTED_AT))
                .isInstanceOf(GuessMatchesAnswerException.class)
                .hasMessage("정답 프롬프트와 동일한 추측은 제출할 수 없습니다.");
    }

    @Test
    @DisplayName("다른 플레이어의 추측과 정규화 후 동일한 추측은 GuessMatchesOthersException을 던진다.")
    void submitGuess_다른_추측과_동일하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);

        // when & then
        assertThatThrownBy(() -> round.submitGuess("guesser-2", "강아지가  기타를 치는 장면", SUBMITTED_AT))
                .isInstanceOf(GuessMatchesOthersException.class)
                .hasMessage("다른 플레이어의 추측과 동일한 추측은 제출할 수 없습니다.");
    }

    @Test
    @DisplayName("출제자를 제외한 모든 참가자가 제출하면 추측 제출 완료로 판단한다.")
    void hasAllGuesses_전원이_제출하면_true를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);

        // when & then
        assertThat(round.hasAllGuesses(participantIds)).isFalse();

        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);

        assertThat(round.hasAllGuesses(participantIds)).isTrue();
    }

    @Test
    @DisplayName("라운드는 출제자와 정답 프롬프트 엔트리를 보관한다.")
    void create_라운드_정보를_보관한다() {
        // given
        PromptEntry answerEntry = createAnswerEntry("questioner", "고양이가 피아노를 치는 장면");

        // when
        Round round = Round.create(1, "questioner", answerEntry);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(round.getRoundNumber()).isEqualTo(1);
            softly.assertThat(round.getQuestionerId()).isEqualTo("questioner");
            softly.assertThat(round.getAnswerEntry()).isSameAs(answerEntry);
            softly.assertThat(round.getGuesses()).isEmpty();
        });
    }

    @Test
    @DisplayName("투표를 열면 정답과 추측을 섞은 보기 목록을 고정한다.")
    void openVoting_보기를_셔플해_고정한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);

        // when
        round.openVoting();

        // then
        List<VoteOption> options = round.getVoteOptions();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(options).hasSize(3);
            softly.assertThat(options)
                    .extracting(VoteOption::getText)
                    .containsExactlyInAnyOrder(
                            "고양이가 피아노를 치는 장면",
                            "강아지가 기타를 치는 장면",
                            "고양이가 드럼을 치는 장면"
                    );
            softly.assertThat(options)
                    .extracting(VoteOption::getOptionId)
                    .doesNotContainNull()
                    .doesNotHaveDuplicates();
        });
    }

    @Test
    @DisplayName("투표를 다시 열어도 이미 고정된 보기 순서는 바뀌지 않는다.")
    void openVoting_다시_열어도_보기_순서가_고정된다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<VoteOption> firstOpened = round.getVoteOptions();

        // when
        round.openVoting();

        // then
        assertThat(round.getVoteOptions()).containsExactlyElementsOf(firstOpened);
    }

    @Test
    @DisplayName("보기에 투표하면 표를 저장한다.")
    void submitVote_보기에_투표하면_표를_저장한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        String answerOptionId = round.getAnswerEntry().getPromptId();

        // when
        round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT.plusSeconds(1));

        // then
        List<Vote> votes = round.getVotes();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(votes).hasSize(1);
            softly.assertThat(votes.get(0).getVoterId()).isEqualTo("guesser-2");
            softly.assertThat(votes.get(0).getOptionId()).isEqualTo(answerOptionId);
            softly.assertThat(votes.get(0).getVotedAt()).isEqualTo(SUBMITTED_AT.plusSeconds(1));
        });
    }

    @Test
    @DisplayName("출제자가 투표하면 VoteNotAllowedException을 던진다.")
    void submitVote_출제자가_투표하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        String answerOptionId = round.getAnswerEntry().getPromptId();

        // when & then
        assertThatThrownBy(() -> round.submitVote("questioner", answerOptionId, SUBMITTED_AT))
                .isInstanceOf(VoteNotAllowedException.class)
                .hasMessage("출제자는 투표할 수 없습니다.");
    }

    @Test
    @DisplayName("같은 플레이어가 두 번 투표하면 DuplicateVoteException을 던진다.")
    void submitVote_중복_투표하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        String answerOptionId = round.getAnswerEntry().getPromptId();
        round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT);

        // when & then
        assertThatThrownBy(() -> round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT))
                .isInstanceOf(DuplicateVoteException.class)
                .hasMessage("이미 투표했습니다.");
    }

    @Test
    @DisplayName("존재하지 않는 보기에 투표하면 InvalidVoteOptionException을 던진다.")
    void submitVote_없는_보기면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();

        // when & then
        assertThatThrownBy(() -> round.submitVote("guesser-2", "unknown-option", SUBMITTED_AT))
                .isInstanceOf(InvalidVoteOptionException.class)
                .hasMessage("존재하지 않는 투표 보기입니다.");
    }

    @Test
    @DisplayName("자신이 제출한 추측에 투표하면 SelfVoteNotAllowedException을 던진다.")
    void submitVote_자기_추측에_투표하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        String ownGuessOptionId = round.getGuesses().get(0).getGuessId();

        // when & then
        assertThatThrownBy(() -> round.submitVote("guesser-1", ownGuessOptionId, SUBMITTED_AT))
                .isInstanceOf(SelfVoteNotAllowedException.class)
                .hasMessage("자신이 제출한 추측에는 투표할 수 없습니다.");
    }

    @Test
    @DisplayName("추측을 제출하지 않은 참가자도 투표할 수 있고, 출제자를 제외한 전원이 투표하면 완료로 판단한다.")
    void hasAllVotes_출제자_제외_전원이_투표하면_true를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2", "guesser-3");
        String answerOptionId = round.getAnswerEntry().getPromptId();
        String guess1OptionId = round.getGuesses().get(0).getGuessId();

        round.submitVote("guesser-1", answerOptionId, SUBMITTED_AT);
        round.submitVote("guesser-2", guess1OptionId, SUBMITTED_AT);

        // when & then
        assertThat(round.hasAllVotes(participantIds)).isFalse();

        round.submitVote("guesser-3", answerOptionId, SUBMITTED_AT);

        assertThat(round.hasAllVotes(participantIds)).isTrue();
    }

    @Test
    @DisplayName("아무도 정답을 맞히지 못하면 출제자 점수는 0점이다.")
    void settleResult_아무도_정답을_맞히지_못하면_출제자_점수는_0점이다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        String guess1OptionId = round.getGuesses().get(0).getGuessId();
        String guess2OptionId = round.getGuesses().get(1).getGuessId();
        round.submitVote("guesser-1", guess2OptionId, SUBMITTED_AT);
        round.submitVote("guesser-2", guess1OptionId, SUBMITTED_AT);

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.getRoundScoreByPlayerId().get("questioner")).isZero();
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-1")).isEqualTo(1);
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-2")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("전원이 정답을 맞히면 출제자 점수는 0점이다.")
    void settleResult_전원이_정답을_맞히면_출제자_점수는_0점이다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        String answerOptionId = round.getAnswerEntry().getPromptId();
        round.submitVote("guesser-1", answerOptionId, SUBMITTED_AT);
        round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT);

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.getRoundScoreByPlayerId().get("questioner")).isZero();
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-1")).isEqualTo(2);
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-2")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("일부만 정답을 맞히면 출제자 점수는 정답 투표수의 2배다.")
    void settleResult_일부만_정답을_맞히면_출제자_점수는_정답_투표수의_2배다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2", "guesser-3");
        String answerOptionId = round.getAnswerEntry().getPromptId();
        String guess1OptionId = round.getGuesses().get(0).getGuessId();
        round.submitVote("guesser-1", answerOptionId, SUBMITTED_AT);
        round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT);
        round.submitVote("guesser-3", guess1OptionId, SUBMITTED_AT);

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.getRoundScoreByPlayerId().get("questioner")).isEqualTo(4);
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-1")).isEqualTo(3);
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-2")).isEqualTo(2);
            softly.assertThat(result.getRoundScoreByPlayerId().get("guesser-3")).isZero();
        });
    }

    @Test
    @DisplayName("같은 추측에 여러 명이 투표하면 낚시 점수는 득표수만큼 누적된다.")
    void settleResult_낚시_점수는_득표수만큼_누적된다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2", "guesser-3");
        String guess1OptionId = round.getGuesses().get(0).getGuessId();
        round.submitVote("guesser-2", guess1OptionId, SUBMITTED_AT);
        round.submitVote("guesser-3", guess1OptionId, SUBMITTED_AT);

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        assertThat(result.getRoundScoreByPlayerId().get("guesser-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("투표하지 않은 기권자가 있어도 출제자 점수 기준인 전체 참가자 수는 줄어들지 않는다.")
    void settleResult_기권자가_있어도_전체_참가자_수는_유지된다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2", "guesser-3");
        String answerOptionId = round.getAnswerEntry().getPromptId();
        round.submitVote("guesser-1", answerOptionId, SUBMITTED_AT);
        round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT);
        // guesser-3는 기권(미투표)

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        assertThat(result.getRoundScoreByPlayerId().get("questioner")).isEqualTo(4);
    }

    @Test
    @DisplayName("결과가 이미 확정되면 다시 호출해도 동일한 결과를 유지한다.")
    void settleResult_재호출해도_동일한_결과를_유지한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.openVoting();
        List<String> answerOnlyParticipants = List.of("questioner", "guesser-1");
        round.settleResult(answerOnlyParticipants);
        RoundResult firstResult = round.getResult();

        // when
        round.settleResult(List.of("questioner", "guesser-1", "guesser-2", "guesser-3"));

        // then
        assertThat(round.getResult()).isSameAs(firstResult);
    }

    private Round createRound(String questionerId, String answerPrompt) {
        return Round.create(1, questionerId, createAnswerEntry(questionerId, answerPrompt));
    }

    private PromptEntry createAnswerEntry(String playerId, String prompt) {
        PromptEntry entry = PromptEntry.waiting(playerId);
        entry.submit(prompt, SUBMITTED_AT);
        entry.completeImageGeneration("https://cdn.example.com/" + playerId + ".png");
        return entry;
    }
}
