package com.igmo.web.websocket.message;

public enum RoomMessageType {
    LOBBY_SNAPSHOT,
    PROMPT_SUBMISSION_SNAPSHOT,
    ROUND_SNAPSHOT,
    VOTE_SNAPSHOT,
    VOTE_SKIPPED_SNAPSHOT,
    ROUND_RESULT_SNAPSHOT,
    GAME_RESULT_SNAPSHOT
}
