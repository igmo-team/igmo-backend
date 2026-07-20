package com.igmo.web.dto;

public record RoomMessage<T>(
        RoomMessageType type,
        T payload
) {

    public static RoomMessage<LobbySnapshot> lobbySnapshot(LobbySnapshot payload) {
        return new RoomMessage<>(RoomMessageType.LOBBY_SNAPSHOT, payload);
    }

    public static RoomMessage<PromptSubmissionSnapshot> promptSubmissionSnapshot(PromptSubmissionSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT, payload);
    }

    public static RoomMessage<RoundSnapshot> roundSnapshot(RoundSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.ROUND_SNAPSHOT, payload);
    }

    public static RoomMessage<VoteSnapshot> voteSnapshot(VoteSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.VOTE_SNAPSHOT, payload);
    }

    public static RoomMessage<RoundResultSnapshot> roundResultSnapshot(RoundResultSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.ROUND_RESULT_SNAPSHOT, payload);
    }

    public static RoomMessage<GameResultSnapshot> gameResultSnapshot(GameResultSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.GAME_RESULT_SNAPSHOT, payload);
    }
}
