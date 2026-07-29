package com.igmo.web.dto;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Round;

public record GuessSubmissionSnapshot(
        String roomCode,
        int roundNumber,
        int totalRoundCount,
        GuessSubmissionStatus status,
        String guess,
        Integer confirmedScore,
        String message
) {

    public static GuessSubmissionSnapshot submitted(GameRoom room, String guess) {
        return from(room, GuessSubmissionStatus.SUBMITTED, guess, null, null);
    }

    public static GuessSubmissionSnapshot rejected(
            GameRoom room,
            String guess,
            String message
    ) {
        return from(room, GuessSubmissionStatus.REJECTED, guess, null, message);
    }

    public static GuessSubmissionSnapshot perfect(GameRoom room, String guess) {
        return from(
                room,
                GuessSubmissionStatus.PERFECT_RETRY_REQUIRED,
                guess,
                3,
                null
        );
    }

    private static GuessSubmissionSnapshot from(
            GameRoom room,
            GuessSubmissionStatus status,
            String guess,
            Integer confirmedScore,
            String message
    ) {
        Round round = room.getCurrentRound();
        return new GuessSubmissionSnapshot(
                room.getCode(),
                round.getRoundNumber(),
                room.getTotalRoundCount(),
                status,
                guess,
                confirmedScore,
                message
        );
    }
}
