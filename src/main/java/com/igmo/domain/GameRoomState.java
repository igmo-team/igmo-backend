package com.igmo.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GameRoomState(
        int schemaVersion,
        long version,
        String roomCode,
        String hostId,
        GamePhase phase,
        Instant lobbyDeadline,
        Instant promptStartedAt,
        Instant promptDeadline,
        Instant finalPromptSubmissionDeadline,
        Instant guessStartedAt,
        Instant guessDeadline,
        Instant finalGuessSubmissionDeadline,
        Instant voteStartedAt,
        Instant voteDeadline,
        Instant resultStartedAt,
        Instant resultDeadline,
        int currentRoundIndex,
        GameStartPolicyState gameStartPolicy,
        List<PlayerState> players,
        List<PromptEntryState> promptEntries,
        List<RoundState> rounds
) {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final int LEGACY_SCHEMA_VERSION = 1;

    public GameRoomState {
        if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "지원하지 않는 게임방 상태 스키마 버전입니다: " + schemaVersion
            );
        }
        if (currentRoundIndex < 0) {
            throw new IllegalArgumentException("현재 라운드 인덱스는 0 이상이어야 합니다.");
        }
        players = List.copyOf(players);
        promptEntries = List.copyOf(promptEntries);
        rounds = List.copyOf(rounds);
    }

    public static GameRoomState from(GameRoom room) {
        synchronized (room) {
            List<PlayerState> players = room.getPlayers().stream()
                    .map(player -> new PlayerState(
                            player.getId(),
                            player.getSecret(),
                            player.getNickname().value(),
                            player.getScore(),
                            player.isReady()
                    ))
                    .toList();

            List<PromptEntryState> promptEntries = room.getPromptEntries().stream()
                    .map(entry -> new PromptEntryState(
                            entry.getPromptId(),
                            entry.getPlayerId(),
                            entry.getPrompt(),
                            entry.getSubmittedAt(),
                            entry.getStatus(),
                            entry.getImageUrl()
                    ))
                    .toList();

            List<RoundState> rounds = room.getRounds().stream()
                    .map(GameRoomState::roundState)
                    .toList();

            return new GameRoomState(
                    CURRENT_SCHEMA_VERSION,
                    room.getVersion(),
                    room.getCode(),
                    room.getHostId(),
                    room.getPhase(),
                    room.getLobbyDeadline(),
                    room.getPromptStartedAt(),
                    room.getPromptDeadline(),
                    room.getFinalPromptSubmissionDeadline(),
                    room.getGuessStartedAt(),
                    room.getGuessDeadline(),
                    room.getFinalGuessSubmissionDeadline(),
                    room.getVoteStartedAt(),
                    room.getVoteDeadline(),
                    room.getResultStartedAt(),
                    room.getResultDeadline(),
                    room.getCurrentRoundIndex(),
                    new GameStartPolicyState(room.getGameStartMinimumPlayers()),
                    players,
                    promptEntries,
                    rounds
            );
        }
    }

    private static RoundState roundState(Round round) {
        RoundResult result = round.getResult();
        return new RoundState(
                round.getRoundNumber(),
                round.getQuestionerId(),
                round.getAnswerEntry().getPromptId(),
                round.getGuesses().stream()
                        .map(guess -> new GuessState(
                                guess.getGuessId(),
                                guess.getPlayerId(),
                                guess.getGuess(),
                                guess.getSubmittedAt()
                        ))
                        .toList(),
                round.getPerfectGuesserIds(),
                round.getVoteOptions().stream()
                        .map(option -> new VoteOptionState(option.getOptionId(), option.getText()))
                        .toList(),
                round.getVotes().stream()
                        .map(vote -> new VoteState(vote.getVoterId(), vote.getOptionId(), vote.getVotedAt()))
                        .toList(),
                round.isVoteSkipped(),
                round.getVoteSkippedStartedAt(),
                round.getVoteSkippedDeadline(),
                result == null ? null : new RoundResultState(result.getScoreDetailsByPlayerId())
        );
    }

    public record GameStartPolicyState(int minimumPlayers) {
    }

    public record PlayerState(
            String id,
            String secret,
            String nickname,
            int score,
            boolean ready
    ) {
    }

    public record PromptEntryState(
            String promptId,
            String playerId,
            String prompt,
            Instant submittedAt,
            PromptEntryStatus status,
            String imageUrl
    ) {
    }

    public record RoundState(
            int roundNumber,
            String questionerId,
            String answerPromptId,
            List<GuessState> guesses,
            List<String> perfectGuesserIds,
            List<VoteOptionState> voteOptions,
            List<VoteState> votes,
            boolean voteSkipped,
            Instant voteSkippedStartedAt,
            Instant voteSkippedDeadline,
            RoundResultState result
    ) {
        public RoundState {
            guesses = List.copyOf(guesses);
            perfectGuesserIds = List.copyOf(perfectGuesserIds);
            voteOptions = List.copyOf(voteOptions);
            votes = List.copyOf(votes);
        }
    }

    public record GuessState(
            String guessId,
            String playerId,
            String guess,
            Instant submittedAt
    ) {
    }

    public record VoteOptionState(String optionId, String text) {
    }

    public record VoteState(String voterId, String optionId, Instant votedAt) {
    }

    public record RoundResultState(Map<String, Map<ScoreReason, Integer>> scoreDetailsByPlayerId) {
        public RoundResultState {
            Map<String, Map<ScoreReason, Integer>> copy = new LinkedHashMap<>();
            scoreDetailsByPlayerId.forEach((playerId, details) -> copy.put(playerId, Map.copyOf(details)));
            scoreDetailsByPlayerId = Map.copyOf(copy);
        }
    }
}
