package com.igmo.domain;

import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.DuplicateVoteException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.InvalidVoteOptionException;
import com.igmo.domain.exception.PerfectGuessAlreadyConfirmedException;
import com.igmo.domain.exception.PerfectGuesserVoteNotAllowedException;
import com.igmo.domain.exception.SelfVoteNotAllowedException;
import com.igmo.domain.exception.VoteNotAllowedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;

public class Round {

    private static final int CORRECT_ANSWER_SCORE = 2;
    private static final int PERFECT_GUESS_SCORE = 3;
    private static final int QUESTIONER_SCORE_PER_CORRECT_VOTE = 2;

    @Getter
    private final int roundNumber;
    @Getter
    private final String questionerId;
    @Getter
    private final PromptEntry answerEntry;
    private final Map<String, GuessEntry> guessesByPlayerId = new LinkedHashMap<>();
    private final Set<String> perfectGuesserIds = new LinkedHashSet<>();
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

    public GuessSubmissionResult submitGuess(String playerId, String guess, Instant submittedAt) {
        if (questionerId.equals(playerId)) {
            throw new GuessNotAllowedException();
        }
        if (guessesByPlayerId.containsKey(playerId)) {
            throw new DuplicateGuessSubmissionException();
        }
        if (matchesAnswer(guess)) {
            if (isPerfectGuesser(playerId)) {
                throw new PerfectGuessAlreadyConfirmedException();
            }
            perfectGuesserIds.add(playerId);
            return GuessSubmissionResult.PERFECT_RETRY_REQUIRED;
        }
        rejectMatchingOtherGuess(guess);
        guessesByPlayerId.put(playerId, GuessEntry.of(playerId, guess, submittedAt));
        return GuessSubmissionResult.SUBMITTED;
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
        return matchesAnswer(guess) || matchesOtherGuess(guess);
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
        if (isPerfectGuesser(voterId)) {
            throw new PerfectGuesserVoteNotAllowedException();
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
                .filter(this::isVoteRequired)
                .allMatch(votesByVoterId::containsKey);
    }

    public int getCompletedVoteCount(Collection<String> participantIds) {
        return (int) participantIds.stream()
                .filter(participantId -> !questionerId.equals(participantId))
                .filter(participantId -> isPerfectGuesser(participantId) || votesByVoterId.containsKey(participantId))
                .count();
    }

    public int getTotalVoteCount(Collection<String> participantIds) {
        return (int) participantIds.stream()
                .filter(participantId -> !questionerId.equals(participantId))
                .count();
    }

    public boolean hasPerfectGuesser(Collection<String> participantIds) {
        return participantIds.stream().anyMatch(this::isPerfectGuesser);
    }

    public boolean hasAllPerfectGuessers(Collection<String> participantIds) {
        List<String> guesserIds = participantIds.stream()
                //출제자를 제외한 참가자만 필터링
                .filter(participantId -> !questionerId.equals(participantId))
                .toList();
        return !guesserIds.isEmpty() && guesserIds.stream().allMatch(this::isPerfectGuesser); //완벽 정답자 인가
        //플레이어가 한명일 땐 출제자는 제외된다. 따라서 빈 스트림이 반환되어서 true를 반환한다.
        // 따라서 출제자를 제외한 참가자가 한 명도 없으면 완벽 정답자도 없으므로 false를 반환해야 한다.
    }

    public List<VoteOption> getVoteOptions() {
        return List.copyOf(voteOptions);
    }

    public Map<String, OwnVoteOption> getOwnVoteOptionsByPlayerId() {
        Map<String, OwnVoteOption> ownVoteOptions = new LinkedHashMap<>();
        ownVoteOptions.put(questionerId, OwnVoteOption.forQuestioner());
        guessesByPlayerId.forEach((playerId, entry) ->
                ownVoteOptions.put(playerId, toOwnVoteOption(playerId, entry)));
        return Collections.unmodifiableMap(ownVoteOptions);
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

    private OwnVoteOption toOwnVoteOption(String playerId, GuessEntry entry) {
        if (isPerfectGuesser(playerId)) {
            return OwnVoteOption.forPerfectGuesser(entry.getGuessId());
        }
        return OwnVoteOption.forGuesser(entry.getGuessId());
    }

    private void rejectMatchingOtherGuess(String guess) {
        if (matchesOtherGuess(guess)) {
            throw new GuessMatchesOthersException();
        }
    }

    private boolean matchesAnswer(String guess) {
        return normalizeAnswer(guess).equals(normalizeAnswer(answerEntry.getPrompt()));
    }

    private boolean matchesOtherGuess(String guess) {
        String normalizedGuess = normalizeGuess(guess);
        return guessesByPlayerId.values().stream()
                .anyMatch(entry -> normalizeGuess(entry.getGuess()).equals(normalizedGuess));
    }

    private String normalizeAnswer(String prompt) {
        return prompt.replaceAll("\\s+", "");
    }

    private String normalizeGuess(String prompt) {
        return prompt.replaceAll("\\s+", "").toLowerCase();
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
        addPerfectGuessScore(scoreDetails);
        addCorrectAnswerScore(scoreDetails);
        addQuestionerScore(scoreDetails, participantIds, voteCounts);

        return scoreDetails;
    }

    private void addPerfectGuessScore(Map<String, Map<ScoreReason, Integer>> scoreDetails) {
        perfectGuesserIds.forEach(playerId -> {
            if (scoreDetails.containsKey(playerId)) {
                scoreDetails.get(playerId).merge(ScoreReason.PERFECT_GUESS, PERFECT_GUESS_SCORE, Integer::sum);
            }
        });
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
                .filter(vote -> !isPerfectGuesser(vote.getVoterId()))
                .filter(this::isCorrectVote)
                .forEach(vote -> scoreDetails.get(vote.getVoterId())
                        .merge(ScoreReason.CORRECT_ANSWER, CORRECT_ANSWER_SCORE, Integer::sum));
    }

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
        long nonQuestionerCount = participantIds.stream()
                .filter(participantId -> !questionerId.equals(participantId))
                .count();
        long perfectGuesserCount = participantIds.stream()
                .filter(this::isPerfectGuesser)
                .count();
        long answerRecognizerCount = correctVoterCount + perfectGuesserCount;
        return correctVoterCount > 0 && answerRecognizerCount < nonQuestionerCount;
    }

    private boolean isCorrectVote(Vote vote) {
        return vote.getOptionId().equals(answerEntry.getPromptId());
    }

    private boolean isPerfectGuesser(String playerId) {
        return perfectGuesserIds.contains(playerId);
    }

    private boolean isVoteRequired(String playerId) {
        return !questionerId.equals(playerId) && !isPerfectGuesser(playerId);
    }
}
