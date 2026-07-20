package com.igmo.domain;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.domain.exception.RoomFullException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;

public class GameRoom {

    private static final int MAX_PLAYERS = 8;
    private static final int MIN_PLAYERS_TO_START = 3;
    private static final AutoPromptPrefix[] AUTO_PROMPT_PREFIXES = AutoPromptPrefix.values();

    @Getter
    private final String code;
    @Getter
    private String hostId;
    @Getter
    private GamePhase phase;
    @Getter
    private Instant promptStartedAt;
    @Getter
    private Instant promptDeadline;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, PromptEntry> promptEntriesByPlayerId = new LinkedHashMap<>();

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
        promptEntriesByPlayerId.remove(playerId);
        if (playerId.equals(hostId) && !players.isEmpty()) {
            assignRandomHost();
        }
        return true;
    }

    public synchronized boolean returnToLobby() {
        if (isInLobby()) {
            return false;
        }
        phase = GamePhase.LOBBY;
        promptStartedAt = null;
        promptDeadline = null;
        promptEntriesByPlayerId.clear();
        players.values().forEach(player -> player.changeReady(false));
        return true;
    }

    public synchronized List<Player> getPlayers() {
        return List.copyOf(players.values());
    }

    public synchronized List<PromptEntry> getPromptEntries() {
        return List.copyOf(promptEntriesByPlayerId.values());
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

    public synchronized void start(String requesterId, Instant startedAt, Duration promptDuration) {
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
        phase = GamePhase.GENERATING;
        promptStartedAt = startedAt;
        promptDeadline = startedAt.plus(promptDuration);
        initializePromptEntries();
    }

    public synchronized void submitPrompt(String playerId, String prompt, Instant submittedAt) {
        if (!isGenerating()) {
            throw new PromptSubmissionNotAllowedException();
        }
        PromptEntry entry = promptEntriesByPlayerId.get(playerId);
        if (entry == null) {
            return;
        }
        if (entry.isSubmitted()) {
            throw new DuplicatePromptSubmissionException();
        }
        if (isPromptExpired(submittedAt)) {
            throw new PromptSubmissionExpiredException();
        }
        entry.submit(prompt, submittedAt);
    }

    public synchronized Map<String, String> autoSubmitPrompts(Instant submittedAt) {
        if (!isGenerating()) {
            return Map.of();
        }

        Map<String, String> autoSubmittedPrompts = new LinkedHashMap<>();
        for (Player player : players.values()) {
            PromptEntry entry = promptEntriesByPlayerId.get(player.getId());
            if (entry == null || !entry.isWaiting()) {
                continue;
            }

            String prompt = createAutoPrompt(player);
            entry.submit(prompt, submittedAt);
            autoSubmittedPrompts.put(player.getId(), prompt);
        }
        return Map.copyOf(autoSubmittedPrompts);
    }

    public synchronized void completeImageGeneration(String playerId, String imageUrl) {
        PromptEntry entry = promptEntriesByPlayerId.get(playerId);
        if (entry == null || !entry.isSubmitted()) {
            return;
        }
        entry.completeImageGeneration(imageUrl);
    }

    public synchronized void failImageGeneration(String playerId) {
        PromptEntry entry = promptEntriesByPlayerId.get(playerId);
        if (entry == null || !entry.isSubmitted()) {
            return;
        }
        entry.failImageGeneration();
    }

    public synchronized void advanceToPlaying() {
        if (!isGenerating() || !hasAllImagesGenerated()) {
            throw new ImagesNotReadyException();
        }
        phase = GamePhase.PLAYING;
    }

    public synchronized boolean hasWaitingPrompt() {
        return promptEntriesByPlayerId.values().stream()
                .anyMatch(PromptEntry::isWaiting);
    }

    public synchronized boolean hasAllImagesGenerated() {
        return !promptEntriesByPlayerId.isEmpty()
                && promptEntriesByPlayerId.values().stream()
                .allMatch(PromptEntry::isImageGenerated);
    }

    public synchronized boolean isPromptExpirationStale(Instant deadline) {
        return promptDeadline == null || !promptDeadline.equals(deadline);
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

    private String createAutoPrompt(Player player) {
        AutoPromptPrefix prefix = AUTO_PROMPT_PREFIXES[
                ThreadLocalRandom.current().nextInt(AUTO_PROMPT_PREFIXES.length)
        ];
        return prefix.value() + " " + player.getNickname().value();
    }

    private boolean isInLobby() {
        return phase == GamePhase.LOBBY;
    }

    private boolean isGenerating() {
        return phase == GamePhase.GENERATING;
    }

    private boolean isPromptExpired(Instant now) {
        return promptDeadline != null && now.isAfter(promptDeadline);
    }

    private void initializePromptEntries() {
        promptEntriesByPlayerId.clear();
        players.keySet().forEach(playerId ->
                promptEntriesByPlayerId.put(playerId, PromptEntry.waiting(playerId)));
    }

    private boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    private boolean hasNickname(Nickname nickname) {
        return players.values().stream()
                .anyMatch(player -> player.getNickname().equals(nickname));
    }
}
