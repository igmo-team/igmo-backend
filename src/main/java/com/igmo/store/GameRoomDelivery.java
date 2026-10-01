package com.igmo.store;

import com.igmo.web.websocket.snapshot.GameResultSnapshot;

/** Local-broker delivery requested by a room-state Pub/Sub event. */
public record GameRoomDelivery(Type type, String playerId, GameResultSnapshot gameResultSnapshot) {

    public GameRoomDelivery(Type type, String playerId) {
        this(type, playerId, null);
    }

    public static GameRoomDelivery roomSnapshot() {
        return new GameRoomDelivery(Type.ROOM_SNAPSHOT, null, null);
    }

    public static GameRoomDelivery imageResult(String playerId) {
        return new GameRoomDelivery(Type.IMAGE_RESULT, playerId, null);
    }

    public static GameRoomDelivery ownVoteOptions() {
        return new GameRoomDelivery(Type.OWN_VOTE_OPTIONS, null, null);
    }

    public static GameRoomDelivery gameResult(GameResultSnapshot snapshot) {
        // Final room state is deleted immediately after publication, so receivers need this fallback.
        return new GameRoomDelivery(Type.GAME_RESULT, null, snapshot);
    }

    public static GameRoomDelivery roomRemoved() {
        return new GameRoomDelivery(Type.ROOM_REMOVED, null, null);
    }

    public static GameRoomDelivery lobbyExpired() {
        return new GameRoomDelivery(Type.LOBBY_EXPIRED, null, null);
    }

    public enum Type {
        ROOM_SNAPSHOT,
        IMAGE_RESULT,
        OWN_VOTE_OPTIONS,
        GAME_RESULT,
        ROOM_REMOVED,
        LOBBY_EXPIRED
    }
}
