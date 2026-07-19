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

    public static RoomMessage<PlayingSnapshot> playingSnapshot(PlayingSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.PLAYING_SNAPSHOT, payload);
    }
}
