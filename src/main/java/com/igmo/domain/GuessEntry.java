package com.igmo.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Getter
public class GuessEntry {

    private final String guessId;
    private final String playerId;
    private final String guess;
    private final Instant submittedAt;

    private GuessEntry(String playerId, String guess, Instant submittedAt) {
        this.guessId = UUID.randomUUID().toString();
        this.playerId = playerId;
        this.guess = guess;
        this.submittedAt = submittedAt;
    }

    public static GuessEntry of(String playerId, String guess, Instant submittedAt) {
        return new GuessEntry(playerId, guess, submittedAt);
    }
}
