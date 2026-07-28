package com.igmo.web.dto;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Round;

public record GuessSubmissionSnapshot(
        String roomCode,
        int roundNumber,
        int totalRoundCount,
        boolean submitted,
        String guess,
        String message
) {

    public static GuessSubmissionSnapshot submitted(GameRoom room, String guess) {
        return from(room, true, guess, null);
    }

    public static GuessSubmissionSnapshot rejected(GameRoom room, String guess, String message) {
        return from(room, false, guess, message);
    }

    private static GuessSubmissionSnapshot from(GameRoom room, boolean submitted, String guess, String message) {
        Round round = room.getCurrentRound();
        return new GuessSubmissionSnapshot(
                room.getCode(),
                round.getRoundNumber(),
                room.getTotalRoundCount(),
                submitted,
                guess,
                message
        );
    }
}
