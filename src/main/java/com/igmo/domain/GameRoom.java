package com.igmo.domain;

import com.igmo.exception.DuplicateNicknameException;
import com.igmo.exception.GameAlreadyStartedException;
import com.igmo.exception.RoomFullException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

public class GameRoom {

    private static final int MAX_PLAYERS = 8;

    @Getter
    private final String code;
    @Getter
    private final String hostId;
    @Getter
    private GamePhase phase;
    private final Map<String, Player> players = new LinkedHashMap<>();

    private GameRoom(String code, Player host) {
        this.code = code;
        this.hostId = host.getId();
        this.phase = GamePhase.LOBBY;
        this.players.put(host.getId(), host);
    }

    public static GameRoom create(String code, Player host) {
        return new GameRoom(code, host);
    }

    public synchronized String addPlayer(Player player) {
        if (!isInLobby()) {
            throw new GameAlreadyStartedException();
        }
        if (isFull()) {
            throw new RoomFullException();
        }
        if (hasNickname(player.getNickname())) {
            throw new DuplicateNicknameException();
        }
        players.put(player.getId(), player);
        return player.getId();
    }

    public synchronized List<Player> getPlayers() {
        return List.copyOf(players.values());
    }

    private boolean isInLobby() {
        return phase == GamePhase.LOBBY;
    }

    private boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    private boolean hasNickname(String nickname) {
        return players.values().stream()
                .anyMatch(player -> player.getNickname().equals(nickname));
    }
}
