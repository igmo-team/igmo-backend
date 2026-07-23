package com.igmo.domain;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.GuessSubmissionExpiredException;
import com.igmo.domain.exception.GuessSubmissionNotAllowedException;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.domain.exception.RoomFullException;
import com.igmo.domain.exception.RoundAdvanceNotAllowedException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.domain.exception.VoteSubmissionExpiredException;
import com.igmo.domain.exception.VoteSubmissionNotAllowedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    @Getter
    private Instant guessStartedAt;
    @Getter
    private Instant guessDeadline;
    @Getter
    private Instant voteStartedAt;
    @Getter
    private Instant voteDeadline;
    @Getter
    private Instant resultStartedAt;
    @Getter
    private Instant resultDeadline;
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

    public synchronized void completeImageGeneration(String playerId, String imageUrl) {
        PromptEntry entry = promptEntriesByPlayerId.get(playerId);
        if (entry == null) {
            return;
        }
        entry.completeImageGeneration(imageUrl);
    }

    public synchronized void failImageGeneration(String playerId) {
        PromptEntry entry = promptEntriesByPlayerId.get(playerId);
        if (entry == null) {
            return;
        }
        entry.failImageGeneration();
    }

    public synchronized boolean isImageGenerationInProgress(String playerId) {
        PromptEntry entry = promptEntriesByPlayerId.get(playerId);
        return entry != null && entry.getStatus() == PromptEntryStatus.GENERATING;
    }

    // 프롬프트 마감 시 READY가 아닌 엔트리에 미리 준비한 샘플을 배정해 전원 READY를 보장한다.
    // 풀이 채울 인원보다 많으면 서로 다른 샘플을, 부족하면 재사용해서라도 빈 자리를 남기지 않는다.
    public synchronized Map<String, SamplePrompt> fillMissingImagesWithSamples(List<SamplePrompt> pool, Instant now) {
        if (!isGenerating() || pool.isEmpty()) {
            return Map.of();
        }
        List<SamplePrompt> shuffledPool = new ArrayList<>(pool);
        Collections.shuffle(shuffledPool, ThreadLocalRandom.current());
        Map<String, SamplePrompt> assignments = new LinkedHashMap<>();
        int assignedCount = 0;
        for (PromptEntry entry : promptEntriesByPlayerId.values()) {
            if (entry.isImageGenerated()) {
                continue;
            }
            SamplePrompt sample = shuffledPool.get(assignedCount % shuffledPool.size());
            entry.fillWithSample(sample.prompt(), sample.imageUrl(), now);
            assignments.put(entry.getPlayerId(), sample);
            assignedCount++;
        }
        return assignments;
    }

    public synchronized void advanceToPlaying() {
        if (!isGenerating() || !hasAllImagesGenerated()) {
            throw new ImagesNotReadyException();
        }
        phase = GamePhase.PLAYING;
    }

    public synchronized boolean hasAllImagesGenerated() {
        return !promptEntriesByPlayerId.isEmpty()
                && promptEntriesByPlayerId.values().stream()
                .allMatch(PromptEntry::isImageGenerated);
    }

    // 모든 현재 참여자의 READY 이미지로 참여 순서대로 라운드를 만들고 첫 라운드의 추측 마감 시각을 설정한다.
    public synchronized void startRounds(Instant startedAt, Duration guessDuration) {
        if (!isGuessing() || !rounds.isEmpty()) {
            throw new RoundStartNotAllowedException();
        }
        List<Round> preparedRounds = prepareRounds();
        if (preparedRounds.size() != players.size()) {
            throw new RoundStartNotAllowedException();
        }
        rounds.addAll(preparedRounds);
        currentRoundIndex = 0;
        guessStartedAt = startedAt;
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

    // 추측 마감 시 미제출자(출제자 제외)에게 닉네임 기반 자동 추측을 채워 넣어 투표 보기 수를 확보한다.
    public synchronized void autoSubmitGuesses(Instant submittedAt) {
        if (!isGuessing()) {
            return;
        }
        Round currentRound = getCurrentRound();
        if (currentRound == null) {
            return;
        }
        for (Player player : players.values()) {
            if (player.getId().equals(currentRound.getQuestionerId())
                    || currentRound.hasGuess(player.getId())) {
                continue;
            }
            currentRound.submitGuess(player.getId(), createAutoGuess(player, currentRound), submittedAt);
        }
    }

    public synchronized void completeGuessSubmission(Instant now, Duration voteDuration) {
        if (!isGuessing()) {
            return;
        }
        if (isGuessExpired(now) || hasAllCurrentRoundGuesses()) {
            openVoting(now, voteDuration);
        }
    }

    public synchronized void submitVote(String voterId, String optionId, Instant votedAt) {
        Round currentRound = getCurrentRound();
        if (!isVoting() || currentRound == null) {
            throw new VoteSubmissionNotAllowedException();
        }
        if (isVoteExpired(votedAt)) {
            throw new VoteSubmissionExpiredException();
        }
        currentRound.submitVote(voterId, optionId, votedAt);
    }

    public synchronized void completeVoting(Instant now, Duration resultDuration) {
        if (!isVoting()) {
            return;
        }
        if (isVoteExpired(now) || hasAllCurrentRoundVotes()) {
            openResults(now, resultDuration);
        }
    }

    // 결과 확인 시간이 지나면 다음 라운드로 넘어가고, 마지막 라운드였다면 게임을 종료한다.
    public synchronized void advanceRound(Instant now, Duration guessDuration) {
        if (!isResults()) {
            throw new RoundAdvanceNotAllowedException();
        }
        if (isLastRound()) {
            phase = GamePhase.ENDED;
            return;
        }
        currentRoundIndex++;
        phase = GamePhase.PLAYING;
        guessStartedAt = now;
        guessDeadline = now.plus(guessDuration);
    }

    public synchronized List<Player> getFinalRanking() {
        return players.values().stream()
                .sorted(Comparator.comparingInt(Player::getScore).reversed())
                .toList();
    }

    public synchronized boolean hasAllCurrentRoundVotes() {
        Round currentRound = getCurrentRound();
        return currentRound != null && currentRound.hasAllVotes(players.keySet());
    }

    public synchronized boolean isVoteExpirationStale(Instant deadline) {
        return voteDeadline == null || !voteDeadline.equals(deadline);
    }

    public synchronized boolean isResultExpirationStale(Instant deadline) {
        return resultDeadline == null || !resultDeadline.equals(deadline);
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

    // 자동 추측이 정답이나 다른 추측과 겹치면 전치사를 다시 뽑아 중복을 회피한다.
    private String createAutoGuess(Player player, Round round) {
        List<AutoPromptPrefix> shuffledPrefixes = new ArrayList<>(List.of(AUTO_PROMPT_PREFIXES));
        Collections.shuffle(shuffledPrefixes, ThreadLocalRandom.current());
        String nickname = player.getNickname().value();
        for (AutoPromptPrefix prefix : shuffledPrefixes) {
            String candidate = prefix.value() + " " + nickname;
            if (!round.hasMatchingGuess(candidate)) {
                return candidate;
            }
        }
        return shuffledPrefixes.get(0).value() + " " + nickname;
    }

    private boolean isInLobby() {
        return phase == GamePhase.LOBBY;
    }

    private boolean isGenerating() {
        return phase == GamePhase.GENERATING;
    }

    private boolean isGuessing() {
        return phase == GamePhase.PLAYING;
    }

    private boolean isVoting() {
        return phase == GamePhase.VOTING;
    }

    private boolean isResults() {
        return phase == GamePhase.RESULTS;
    }

    private boolean isLastRound() {
        return currentRoundIndex >= rounds.size() - 1;
    }

    // VOTING 전환과 보기 확정, 투표 마감 설정은 함께 일어나야 하는 하나의 전이다.
    private void openVoting(Instant openedAt, Duration voteDuration) {
        Round currentRound = getCurrentRound();
        if (currentRound == null) {
            return;
        }
        phase = GamePhase.VOTING;
        currentRound.openVoting();
        voteStartedAt = openedAt;
        voteDeadline = openedAt.plus(voteDuration);
    }

    // RESULTS 전환과 점수 확정, 점수 반영, 결과 마감 설정은 함께 일어나야 하는 하나의 전이다.
    private void openResults(Instant openedAt, Duration resultDuration) {
        Round currentRound = getCurrentRound();
        if (currentRound == null) {
            return;
        }
        phase = GamePhase.RESULTS;
        currentRound.settleResult(players.keySet());
        applyRoundScore(currentRound.getResult());
        resultStartedAt = openedAt;
        resultDeadline = openedAt.plus(resultDuration);
    }

    private void applyRoundScore(RoundResult result) {
        result.getRoundScoreByPlayerId().forEach((playerId, score) -> {
            Player player = players.get(playerId);
            if (player != null) {
                player.addScore(score);
            }
        });
    }

    private boolean isPromptExpired(Instant now) {
        return promptDeadline != null && now.isAfter(promptDeadline);
    }

    private boolean isGuessExpired(Instant now) {
        return guessDeadline != null && now.isAfter(guessDeadline);
    }

    private boolean isVoteExpired(Instant now) {
        return voteDeadline != null && now.isAfter(voteDeadline);
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
