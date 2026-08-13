package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.DuplicateVoteException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.InvalidVoteOptionException;
import com.igmo.domain.exception.PerfectGuesserVoteNotAllowedException;
import com.igmo.domain.exception.PerfectGuessAlreadyConfirmedException;
import com.igmo.domain.exception.SelfVoteNotAllowedException;
import com.igmo.domain.exception.VoteNotAllowedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    @DisplayName("정답 프롬프트와 공백만 다른 추측은 PERFECT 재입력 상태를 반환한다.")
    void submitGuess_정답과_동일하면_PERFECT_재입력_상태를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");

        // when
        GuessSubmissionResult result = round.submitGuess(
                "guesser-1",
                "  고양이가   피아노를 치는   장면 ",
                SUBMITTED_AT
        );

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(GuessSubmissionResult.PERFECT_RETRY_REQUIRED);
            softly.assertThat(round.getGuesses()).isEmpty();
        });
    }

    @Test
    @DisplayName("PERFECT를 다시 제출하면 PerfectGuessAlreadyConfirmedException을 던진다.")
    void submitGuess_PERFECT를_재제출하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "뛰어노는 강아지");

        // when
        GuessSubmissionResult first = round.submitGuess("guesser-1", "뛰어노는강아지", SUBMITTED_AT);
        // then
        assertThat(first).isEqualTo(GuessSubmissionResult.PERFECT_RETRY_REQUIRED);
        assertThatThrownBy(() -> round.submitGuess("guesser-1", "뛰어 노는 강아지", SUBMITTED_AT.plusSeconds(1)))
                .isInstanceOf(PerfectGuessAlreadyConfirmedException.class)
                .hasMessage("이미 완벽 정답을 맞혔습니다. 투표용 가짜 프롬프트를 입력하세요.");
        assertThat(round.getGuesses()).isEmpty();
    }

    @Test
    @DisplayName("영문 대소문자만 다른 추측은 가짜 프롬프트로 저장한다.")
    void submitGuess_대소문자만_다르면_가짜_프롬프트로_저장한다() {
        // given
        Round round = createRound("questioner", "AI Robot이 그린 그림");

        // when
        GuessSubmissionResult result = round.submitGuess("guesser-1", "ai robot이 그린 그림", SUBMITTED_AT);

        // then
        assertThat(result).isEqualTo(GuessSubmissionResult.SUBMITTED);
        assertThat(round.getGuesses()).singleElement().extracting(GuessEntry::getGuess)
                .isEqualTo("ai robot이 그린 그림");
    }

    @Test
    @DisplayName("PERFECT를 맞힌 플레이어는 가짜 프롬프트를 별도로 제출할 수 있다.")
    void submitGuess_PERFECT_후_가짜_프롬프트를_제출할_수_있다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);

        // when
        GuessSubmissionResult result = round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT.plusSeconds(1));

        // then
        assertThat(result).isEqualTo(GuessSubmissionResult.SUBMITTED);
        assertThat(round.getGuesses()).singleElement().extracting(GuessEntry::getPlayerId)
                .isEqualTo("guesser-1");
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
    @DisplayName("PERFECT만 맞힌 플레이어는 가짜 프롬프트를 내기 전까지 추측 완료로 계산하지 않는다.")
    void hasAllGuesses_PERFECT만_맞히면_false를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "강아지가 기타를 치는 장면", SUBMITTED_AT);

        // when & then
        assertThat(round.hasAllGuesses(participantIds)).isFalse();
    }

    @Test
    @DisplayName("추측자가 없으면 모든 PERFECT 추측자로 판단하지 않는다.")
    void hasAllPerfectGuessers_추측자가_없으면_false를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");

        // when & then
        assertThat(round.hasAllPerfectGuessers(List.of("questioner"))).isFalse();
    }

    @Test
    @DisplayName("추측자가 모두 PERFECT면 모든 PERFECT 추측자로 판단한다.")
    void hasAllPerfectGuessers_모두_PERFECT면_true를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가피아노를치는장면", SUBMITTED_AT);

        // when & then
        assertThat(round.hasAllPerfectGuessers(List.of("questioner", "guesser-1", "guesser-2"))).isTrue();
    }

    @Test
    @DisplayName("일부 추측자만 PERFECT면 모든 PERFECT 추측자로 판단하지 않는다.")
    void hasAllPerfectGuessers_일부만_PERFECT면_false를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "강아지가 기타를 치는 장면", SUBMITTED_AT);

        // when & then
        assertThat(round.hasAllPerfectGuessers(List.of("questioner", "guesser-1", "guesser-2"))).isFalse();
    }

    @Test
    @DisplayName("PERFECT 추측자가 없으면 모든 PERFECT 추측자로 판단하지 않는다.")
    void hasAllPerfectGuessers_아무도_PERFECT가_아니면_false를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);

        // when & then
        assertThat(round.hasAllPerfectGuessers(List.of("questioner", "guesser-1", "guesser-2"))).isFalse();
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
    @DisplayName("출제자는 본인 이미지(ownImage=true), 추측자는 본인 보기 id를 담아 반환한다.")
    void getOwnVoteOptionsByPlayerId_출제자와_추측자별_본인_보기_정보를_반환한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT);
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT);
        String guess1OptionId = round.getGuesses().get(0).getGuessId();
        String guess2OptionId = round.getGuesses().get(1).getGuessId();

        // when
        Map<String, OwnVoteOption> ownVoteOptions = round.getOwnVoteOptionsByPlayerId();

        // then
        assertThat(ownVoteOptions).containsOnly(
                entry("questioner", OwnVoteOption.forQuestioner()),
                entry("guesser-1", OwnVoteOption.forGuesser(guess1OptionId)),
                entry("guesser-2", OwnVoteOption.forGuesser(guess2OptionId)));
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
    @DisplayName("PERFECT 플레이어가 투표하면 PerfectGuesserVoteNotAllowedException을 던진다.")
    void submitVote_PERFECT_플레이어가_투표하면_예외를_던진다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT.plusSeconds(1));
        round.openVoting();
        String answerOptionId = round.getAnswerEntry().getPromptId();

        // when & then
        assertThatThrownBy(() -> round.submitVote("guesser-1", answerOptionId, SUBMITTED_AT.plusSeconds(2)))
                .isInstanceOf(PerfectGuesserVoteNotAllowedException.class)
                .hasMessage("완벽 정답자는 투표할 수 없습니다.");
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
    @DisplayName("PERFECT 플레이어는 투표 없이 완료 인원으로 계산한다.")
    void hasAllVotes_PERFECT_플레이어를_완료_인원으로_계산한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT.plusSeconds(1));
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT.plusSeconds(2));
        round.openVoting();
        round.submitVote("guesser-2", round.getAnswerEntry().getPromptId(), SUBMITTED_AT.plusSeconds(3));

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(round.hasAllVotes(participantIds)).isTrue();
            softly.assertThat(round.getCompletedVoteCount(participantIds)).isEqualTo(2);
            softly.assertThat(round.getTotalVoteCount(participantIds)).isEqualTo(2);
            softly.assertThat(round.hasPerfectGuesser(participantIds)).isTrue();
        });
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
    @DisplayName("PERFECT 참가자와 정답 투표자로 비출제자 전원이 정답을 인지하면 출제자 점수는 0점이다.")
    void settleResult_PERFECT와_정답_투표자로_전원이_정답을_인지하면_출제자_점수는_0점이다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT.plusSeconds(1));
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT.plusSeconds(2));
        round.openVoting();
        String answerOptionId = round.getAnswerEntry().getPromptId();
        round.submitVote("guesser-2", answerOptionId, SUBMITTED_AT.plusSeconds(3));

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.getRoundScore("questioner")).isZero();
            softly.assertThat(result.getRoundScore("guesser-1")).isEqualTo(3);
            softly.assertThat(result.getRoundScore("guesser-2")).isEqualTo(2);
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
    @DisplayName("라운드 점수를 낚시·정답·출제자 유형으로 나눠 보관한다.")
    void settleResult_점수를_유형별로_분해해_보관한다() {
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
            softly.assertThat(result.getScoreDetails("questioner"))
                    .containsOnly(entry(ScoreReason.QUESTIONER, 4));
            softly.assertThat(result.getScoreDetails("guesser-1"))
                    .containsOnly(entry(ScoreReason.CORRECT_ANSWER, 2), entry(ScoreReason.FOOLED_PLAYER, 1));
            softly.assertThat(result.getScoreDetails("guesser-2"))
                    .containsOnly(entry(ScoreReason.CORRECT_ANSWER, 2));
            softly.assertThat(result.getScoreDetails("guesser-3")).isEmpty();
        });
    }

    @Test
    @DisplayName("PERFECT 점수는 한 번 반영되고 낚시 점수와 합산된다.")
    void settleResult_PERFECT_점수는_한번만_반영하고_낚시_점수와_합산한다() {
        // given
        Round round = createRound("questioner", "고양이가 피아노를 치는 장면");
        List<String> participantIds = List.of("questioner", "guesser-1", "guesser-2");
        round.submitGuess("guesser-1", "고양이가피아노를치는장면", SUBMITTED_AT);
        round.submitGuess("guesser-1", "강아지가 기타를 치는 장면", SUBMITTED_AT.plusSeconds(1));
        round.submitGuess("guesser-2", "고양이가 드럼을 치는 장면", SUBMITTED_AT.plusSeconds(2));
        round.openVoting();
        String perfectGuesserOptionId = round.getGuesses().get(0).getGuessId();
        round.submitVote("guesser-2", perfectGuesserOptionId, SUBMITTED_AT.plusSeconds(3));

        // when
        round.settleResult(participantIds);

        // then
        RoundResult result = round.getResult();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.getScoreDetails("guesser-1"))
                    .containsOnly(
                            entry(ScoreReason.PERFECT_GUESS, 3),
                            entry(ScoreReason.FOOLED_PLAYER, 1)
                    );
            softly.assertThat(result.getRoundScore("guesser-1")).isEqualTo(4);
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
