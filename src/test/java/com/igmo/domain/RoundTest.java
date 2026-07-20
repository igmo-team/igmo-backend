package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessMatchesAnswerException;
import com.igmo.domain.exception.GuessMatchesOthersException;
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
