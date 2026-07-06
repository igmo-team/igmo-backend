package com.igmo.web.dto;

public record RoomMessage<T>(
        RoomMessageType type,
        T payload
) {

    public static RoomMessage<LobbySnapshot> lobbySnapshot(LobbySnapshot payload) {
        return new RoomMessage<>(RoomMessageType.LOBBY_SNAPSHOT, payload);
    }

    public static RoomMessage<PromptEntriesSnapshot> promptEntriesSnapshot(PromptEntriesSnapshot payload) {
        return new RoomMessage<>(RoomMessageType.PROMPT_ENTRIES_SNAPSHOT, payload);
    }
}
