package com.igmo.domain;

public final class GameStartPolicy {

    private static final int STANDARD_MINIMUM_PLAYERS = 3;
    private static final int LOCAL_MINIMUM_PLAYERS = 1;

    private final int minimumPlayers;

    private GameStartPolicy(int minimumPlayers) {
        if (minimumPlayers < 1) {
            throw new IllegalArgumentException("게임 시작 최소 인원은 1명 이상이어야 합니다.");
        }
        this.minimumPlayers = minimumPlayers;
    }

    public static GameStartPolicy standard() {
        return new GameStartPolicy(STANDARD_MINIMUM_PLAYERS);
    }

    public static GameStartPolicy local() {
        return new GameStartPolicy(LOCAL_MINIMUM_PLAYERS);
    }

    public boolean canStart(int playerCount) {
        return playerCount >= minimumPlayers;
    }

    public int minimumPlayers() {
        return minimumPlayers;
    }
}
