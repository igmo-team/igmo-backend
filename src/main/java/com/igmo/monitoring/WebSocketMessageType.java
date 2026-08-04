package com.igmo.monitoring;

import com.igmo.web.dto.RoomMessageType;

public enum WebSocketMessageType {
    LOBBY_SNAPSHOT,
    PROMPT_SUBMISSION_SNAPSHOT,
    ROUND_SNAPSHOT,
    VOTE_SNAPSHOT,
    ROUND_RESULT_SNAPSHOT,
    GAME_RESULT_SNAPSHOT,
    IMAGE_GENERATION_EVENT,
    GUESS_SUBMISSION_RESULT,
    OWN_VOTE_OPTION_NOTICE;

    public static WebSocketMessageType from(RoomMessageType roomMessageType) {
        return valueOf(roomMessageType.name());
    }
}
