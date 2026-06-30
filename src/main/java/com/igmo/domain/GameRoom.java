package com.igmo.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * 가변 상태(players, phase)는 스레드 안전하지 않다.
 * 검증과 추가처럼 여러 메서드를 묶어 처리하는 호출은
 * 호출 측에서 {@code synchronized(room)}으로 원자성을 보장해야 한다.
 */
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

    public String addPlayer(Player player) {
        players.put(player.getId(), player);
        return player.getId();
    }

    public boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    public boolean hasNickname(String nickname) {
        return players.values().stream()
                .anyMatch(player -> player.getNickname().equals(nickname));
    }

    public boolean isInLobby() {
        return phase == GamePhase.LOBBY;
    }

    public List<Player> getPlayers() {
        return List.copyOf(players.values());
    }
}
