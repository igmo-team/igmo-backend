package com.igmo.domain;

import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.GuessNotAllowedException;
import com.igmo.domain.exception.GuessMatchesAnswerException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

public class Round {

    @Getter
    private final int roundNumber;
    @Getter
    private final String questionerId;
    @Getter
    private final PromptEntry answerEntry;
    private final Map<String, GuessEntry> guessesByPlayerId = new LinkedHashMap<>();

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
