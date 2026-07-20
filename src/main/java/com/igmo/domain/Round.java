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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;

public class Round {

    @Getter
    private final int roundNumber;
    @Getter
    private final String questionerId;
    @Getter
    private final PromptEntry answerEntry;
    private final Map<String, GuessEntry> guessesByPlayerId = new LinkedHashMap<>();
    private final List<VoteOption> voteOptions = new ArrayList<>();
    private final Map<String, Vote> votesByVoterId = new LinkedHashMap<>();

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
        if (normalizedGuess.equals(normalize(answerEntry.getPrompt()))) {
            throw new GuessMatchesAnswerException();
        }
        boolean matchesOthers = guessesByPlayerId.values().stream()
                .anyMatch(entry -> normalize(entry.getGuess()).equals(normalizedGuess));
        if (matchesOthers) {
            throw new GuessMatchesOthersException();
        }
    }

    private String normalize(String prompt) {
        return prompt.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
