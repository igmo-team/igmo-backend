package com.igmo.domain;

import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.DuplicateVoteException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessMatchesAnswerException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.InvalidVoteOptionException;
import com.igmo.domain.exception.SelfVoteNotAllowedException;
import com.igmo.domain.exception.VoteNotAllowedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;

public class Round {

    private static final int CORRECT_ANSWER_SCORE = 2;
    private static final int QUESTIONER_SCORE_PER_CORRECT_VOTE = 2;

    @Getter
    private final int roundNumber;
    @Getter
    private final String questionerId;
    @Getter
    private final PromptEntry answerEntry;
    private final Map<String, GuessEntry> guessesByPlayerId = new LinkedHashMap<>();
    private final List<VoteOption> voteOptions = new ArrayList<>();
    private final Map<String, Vote> votesByVoterId = new LinkedHashMap<>();
    private RoundResult result;

    private Round(int roundNumber, String questionerId, PromptEntry answerEntry) {
        this.roundNumber = roundNumber;
        this.questionerId = questionerId;
        this.answerEntry = answerEntry;
    }

    public static Round create(int roundNumber, String questionerId, PromptEntry answerEntry) {
        return new Round(roundNumber, questionerId, answerEntry);
    }

    public void submitGuess(String playerId, String guess, Instant submittedAt) {
        if (questionerId.equals(playerId)) {
            throw new GuessNotAllowedException();
        }
        if (guessesByPlayerId.containsKey(playerId)) {
            throw new DuplicateGuessSubmissionException();
        }
        rejectMatchingGuess(guess);
        guessesByPlayerId.put(playerId, GuessEntry.of(playerId, guess, submittedAt));
    }

    public boolean hasAllGuesses(Collection<String> participantIds) {
        return participantIds.stream()
                .filter(participantId -> !questionerId.equals(participantId))
                .allMatch(guessesByPlayerId::containsKey);
    }

    public boolean hasGuess(String playerId) {
        return guessesByPlayerId.containsKey(playerId);
    }

    // 자동 추측 생성 시 정답이나 기존 추측과 겹치는지 검사한다.
    public boolean hasMatchingGuess(String guess) {
        String normalizedGuess = normalize(guess);
        return matchesAnswer(normalizedGuess) || matchesOtherGuess(normalizedGuess);
    }

    public List<GuessEntry> getGuesses() {
        return List.copyOf(guessesByPlayerId.values());
    }

    // 보기 순서는 한 번 셔플해 고정한다. 이미 열린 투표를 다시 열어도 순서가 바뀌지 않는다.
    public void openVoting() {
        if (!voteOptions.isEmpty()) {
            return;
        }
        voteOptions.add(VoteOption.of(answerEntry.getPromptId(), answerEntry.getPrompt()));
        guessesByPlayerId.values().forEach(entry ->
                voteOptions.add(VoteOption.of(entry.getGuessId(), entry.getGuess())));
        Collections.shuffle(voteOptions, ThreadLocalRandom.current());
    }

    public void submitVote(String voterId, String optionId, Instant votedAt) {
        if (questionerId.equals(voterId)) {
            throw new VoteNotAllowedException();
        }
        if (votesByVoterId.containsKey(voterId)) {
            throw new DuplicateVoteException();
        }
        if (!hasVoteOption(optionId)) {
            throw new InvalidVoteOptionException();
        }
        if (isOwnGuess(voterId, optionId)) {
            throw new SelfVoteNotAllowedException();
        }
        votesByVoterId.put(voterId, Vote.of(voterId, optionId, votedAt));
    }

    public boolean hasAllVotes(Collection<String> participantIds) {
        return participantIds.stream()
                .filter(participantId -> !questionerId.equals(participantId))
                .allMatch(votesByVoterId::containsKey);
    }

    public List<VoteOption> getVoteOptions() {
        return List.copyOf(voteOptions);
    }

    public List<Vote> getVotes() {
        return List.copyOf(votesByVoterId.values());
    }

    // 결과는 한 번만 확정한다. 이미 확정된 결과는 다시 계산하지 않는다.
    public void settleResult(Collection<String> participantIds) {
        if (result != null) {
            return;
        }
        Map<String, Integer> voteCounts = aggregateVotesByOption();
        result = RoundResult.of(calculateScoreDetails(participantIds, voteCounts));
    }

    public RoundResult getResult() {
        return result;
    }

    private boolean hasVoteOption(String optionId) {
        return voteOptions.stream()
                .anyMatch(option -> option.getOptionId().equals(optionId));
    }

    private boolean isOwnGuess(String voterId, String optionId) {
        GuessEntry ownGuess = guessesByPlayerId.get(voterId);
        return ownGuess != null && ownGuess.getGuessId().equals(optionId);
    }

    private void rejectMatchingGuess(String guess) {
        String normalizedGuess = normalize(guess);
        if (matchesAnswer(normalizedGuess)) {
            throw new GuessMatchesAnswerException();
        }
        if (matchesOtherGuess(normalizedGuess)) {
            throw new GuessMatchesOthersException();
        }
    }

    private boolean matchesAnswer(String normalizedGuess) {
        return normalizedGuess.equals(normalize(answerEntry.getPrompt()));
    }

    private boolean matchesOtherGuess(String normalizedGuess) {
        return guessesByPlayerId.values().stream()
                .anyMatch(entry -> normalize(entry.getGuess()).equals(normalizedGuess));
    }

    private String normalize(String prompt) {
        return prompt.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private Map<String, Integer> aggregateVotesByOption() {
        Map<String, Integer> voteCounts = new LinkedHashMap<>();
        voteOptions.forEach(option -> voteCounts.put(option.getOptionId(), 0));
        votesByVoterId.values().forEach(vote ->
                voteCounts.merge(vote.getOptionId(), 1, Integer::sum));
        return voteCounts;
    }

    // 플레이어별 점수를 유형(낚시/정답/출제자)으로 나눠 계산한다. 얻은 점수가 없는 유형은 담지 않는다.
    private Map<String, Map<ScoreReason, Integer>> calculateScoreDetails(
            Collection<String> participantIds,
            Map<String, Integer> voteCounts
    ) {
        Map<String, Map<ScoreReason, Integer>> scoreDetails = new LinkedHashMap<>();
        participantIds.forEach(participantId -> scoreDetails.put(participantId, new EnumMap<>(ScoreReason.class)));

        addCatchScore(scoreDetails, voteCounts);
        addCorrectAnswerScore(scoreDetails);
        addQuestionerScore(scoreDetails, participantIds, voteCounts);

        return scoreDetails;
    }

    // 낚시 점수: 내 추측에 투표한 사람 수만큼 득점한다.
    private void addCatchScore(
            Map<String, Map<ScoreReason, Integer>> scoreDetails,
            Map<String, Integer> voteCounts
    ) {
        guessesByPlayerId.values().forEach(guess -> {
            int catchScore = voteCounts.getOrDefault(guess.getGuessId(), 0);
            if (catchScore > 0) {
                scoreDetails.get(guess.getPlayerId()).merge(ScoreReason.FOOLED_PLAYER, catchScore, Integer::sum);
            }
        });
    }

    // 정답 점수: 정답 프롬프트를 맞힌 사람은 각각 득점한다.
    private void addCorrectAnswerScore(Map<String, Map<ScoreReason, Integer>> scoreDetails) {
        votesByVoterId.values().stream()
                .filter(this::isCorrectVote)
                .forEach(vote -> scoreDetails.get(vote.getVoterId())
                        .merge(ScoreReason.CORRECT_ANSWER, CORRECT_ANSWER_SCORE, Integer::sum));
    }

    // 출제자 점수: 정답 투표자가 1명 이상이고 전원이 아닐 때만 투표수에 비례해 득점한다.
    private void addQuestionerScore(
            Map<String, Map<ScoreReason, Integer>> scoreDetails,
            Collection<String> participantIds,
            Map<String, Integer> voteCounts
    ) {
        int correctVoterCount = voteCounts.getOrDefault(answerEntry.getPromptId(), 0);
        if (isQuestionerRewarded(correctVoterCount, participantIds)) {
            scoreDetails.get(questionerId)
                    .merge(ScoreReason.QUESTIONER, correctVoterCount * QUESTIONER_SCORE_PER_CORRECT_VOTE, Integer::sum);
        }
    }

    private boolean isQuestionerRewarded(int correctVoterCount, Collection<String> participantIds) {
        long eligibleVoterCount = participantIds.stream()
                .filter(participantId -> !questionerId.equals(participantId))
                .count();
        return correctVoterCount > 0 && correctVoterCount < eligibleVoterCount;
    }

    private boolean isCorrectVote(Vote vote) {
        return vote.getOptionId().equals(answerEntry.getPromptId());
    }
}
