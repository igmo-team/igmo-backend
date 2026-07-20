package com.igmo.domain;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.GuessSubmissionExpiredException;
import com.igmo.domain.exception.GuessSubmissionNotAllowedException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.domain.exception.RoomFullException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
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
    @Getter
    private Instant promptStartedAt;
    @Getter
    private Instant promptDeadline;
    @Getter
    private Instant guessDeadline;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, PromptEntry> promptEntriesByPlayerId = new LinkedHashMap<>();
    private final List<Round> rounds = new ArrayList<>();
    private int currentRoundIndex;

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
        if (!isPrompting()) {
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

    public synchronized void completePromptSubmission(Instant now) {
        if (!isPrompting()) {
            return;
        }
        if (isPromptExpired(now)) {
            phase = GamePhase.GENERATING; //TODO 임시 처리함 -> 변경 필요
            return;
        }
        if (!hasWaitingPrompt()) {
            phase = GamePhase.GENERATING; //TODO 임시 처리함 -> 변경 필요
        }
    }

    public synchronized boolean hasWaitingPrompt() {
        return promptEntriesByPlayerId.values().stream()
                .anyMatch(PromptEntry::isWaiting);
    }

    //  READY 이미지 엔트리를 가진 플레이어들로 참여 순서대로 라운드 목록을 만들고 첫 라운드의 추측 마감 시각을 설정한다.
    //  PLAYING이 아니거나 이미 라운드가 있거나 READY 엔트리가 없으면 RoundStartNotAllowedException을 던진다.
    public synchronized void startRounds(Instant startedAt, Duration guessDuration) {
        if (!isGuessing() || !rounds.isEmpty()) {
            throw new RoundStartNotAllowedException();
        }
        List<Round> preparedRounds = prepareRounds();
        if (preparedRounds.isEmpty()) {
            throw new RoundStartNotAllowedException();
        }
        rounds.addAll(preparedRounds);
        currentRoundIndex = 0;
        guessDeadline = startedAt.plus(guessDuration);
    }

    public synchronized void submitGuess(String playerId, String guess, Instant submittedAt) {
        Round currentRound = getCurrentRound();
        if (!isGuessing() || currentRound == null) {
            throw new GuessSubmissionNotAllowedException();
        }
        if (isGuessExpired(submittedAt)) {
            throw new GuessSubmissionExpiredException();
        }
        currentRound.submitGuess(playerId, guess, submittedAt);
    }

    public synchronized void completeGuessSubmission(Instant now) {
        if (!isGuessing()) {
            return;
        }
        if (isGuessExpired(now) || hasAllCurrentRoundGuesses()) {
            phase = GamePhase.VOTING;
        }
    }

    public synchronized boolean hasAllCurrentRoundGuesses() {
        Round currentRound = getCurrentRound();
        return currentRound != null && currentRound.hasAllGuesses(players.keySet());
    }

    public synchronized boolean isGuessExpirationStale(Instant deadline) {
        return guessDeadline == null || !guessDeadline.equals(deadline);
    }

    public synchronized Round getCurrentRound() {
        if (rounds.isEmpty()) {
            return null;
        }
        return rounds.get(currentRoundIndex);
    }

    public synchronized int getTotalRoundCount() {
        return rounds.size();
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

    private boolean isInLobby() {
        return phase == GamePhase.LOBBY;
    }

    private boolean isPrompting() {
        return phase == GamePhase.GENERATING;
    }

    private boolean isGuessing() {
        return phase == GamePhase.PLAYING;
    }

    private boolean isPromptExpired(Instant now) {
        return promptDeadline != null && now.isAfter(promptDeadline);
    }

    private boolean isGuessExpired(Instant now) {
        return guessDeadline != null && now.isAfter(guessDeadline);
    }

    private List<Round> prepareRounds() {
        List<Round> preparedRounds = new ArrayList<>();
        for (Player player : players.values()) {
            PromptEntry entry = promptEntriesByPlayerId.get(player.getId());
            if (entry != null && entry.getStatus() == PromptEntryStatus.READY) {
                preparedRounds.add(Round.create(preparedRounds.size() + 1, player.getId(), entry));
            }
        }
        return preparedRounds;
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
