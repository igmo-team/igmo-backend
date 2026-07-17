package com.igmo.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.RoomFullException;
import lombok.Getter;

public class GameRoom {

    private static final int MAX_PLAYERS = 8;
    private static final int MIN_PLAYERS_TO_START = 3;

    @Getter
    private final String code;
    @Getter
    private String hostId;
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

    public synchronized boolean removePlayer(String playerId) {
        if (players.remove(playerId) == null) {
            return false;
        }
        if (playerId.equals(hostId) && !players.isEmpty()) {
            assignRandomHost();
        }
        return true;
    }

    public synchronized List<Player> getPlayers() {
        return List.copyOf(players.values());
    }

    public synchronized boolean isEmpty() {
        return players.isEmpty();
    }

    public synchronized boolean hasPlayer(String playerId) {
        return players.containsKey(playerId);
    }

    public synchronized boolean isSecretValid(String playerId, String secret) {
        Player player = players.get(playerId);
        return player != null && player.getSecret().equals(secret);
    }

    public synchronized void changePlayerReady(String playerId, boolean ready) {
        if (!isInLobby()) {
            throw new GameAlreadyStartedException();
        }
        Player player = players.get(playerId);
        if (player == null) {
            return;
        }
        player.changeReady(ready);
    }

    public synchronized void start(String requesterId) {
        if (!isInLobby()) {
            throw new GameAlreadyStartedException();
        }
        if (!requesterId.equals(hostId)) {
            throw new NotHostException();
        }
        if (players.size() < MIN_PLAYERS_TO_START) {
            throw new InsufficientPlayersException();
        }
        if (!allOthersReady()) {
            throw new PlayersNotReadyException();
        }
        phase = GamePhase.PROMPTING;
    }

    private boolean allOthersReady() {
        return players.values().stream()
                .filter(player -> !player.getId().equals(hostId))
                .allMatch(Player::isReady);
    }

    private void assignRandomHost() {
        List<Player> remaining = List.copyOf(players.values());
        hostId = remaining.get(ThreadLocalRandom.current().nextInt(remaining.size())).getId();
    }

    private boolean isInLobby() {
        return phase == GamePhase.LOBBY;
    }

    private boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    private boolean hasNickname(Nickname nickname) {
        return players.values().stream()
                .anyMatch(player -> player.getNickname().equals(nickname));
    }
}
