package com.igmo.service;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.GameResultSnapshot;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteSkippedSnapshot;
import com.igmo.web.dto.VoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VoteResultPhaseService {

    private final GameRoomRepository gameRoomRepository;
    private final GamePhaseScheduler gamePhaseScheduler;
    private final GameEventPublisher eventPublisher;
    private final GameDrainLifecycle gameDrainLifecycle;
    private final GuessPhaseService guessPhaseService;

    @Value("${igmo.game.vote-duration}")
    private Duration voteDuration;
    @Value("${igmo.game.vote-skipped-duration}")
    private Duration voteSkippedDuration;
    @Value("${igmo.game.result-duration}")
    private Duration resultDuration;
    @Value("${igmo.game.guess-duration}")
    private Duration guessDuration;

    public void submitVote(String code, String playerId, String optionId) {
        RoomMessage<?> message = gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            GamePhase fromPhase = room.getPhase();
            Instant submittedAt = Instant.now();
            room.submitVote(playerId, optionId, submittedAt);
            if (room.hasAllCurrentRoundVotes()) {
                gamePhaseScheduler.cancelVote(code);
                room.completeVoting(submittedAt, resultDuration);
                GamePhaseService.logPhaseTransition(code, fromPhase, room.getPhase());
                scheduleResultExpiration(code, room.getResultDeadline());
                return RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room));
            }
            GamePhaseService.logPhaseTransition(code, fromPhase, room.getPhase());
            return RoomMessage.voteSnapshot(VoteSnapshot.from(room));
        });
        eventPublisher.publish(code, message);
    }

    RoomMessage<?> completeGuessSubmission(GameRoom room, Instant completedAt) {
        String code = room.getCode();
        GamePhase fromPhase = room.getPhase();
        room.completeGuessSubmission(completedAt, voteDuration, voteSkippedDuration);
        GamePhaseService.logPhaseTransition(code, fromPhase, room.getPhase());

        if (room.getPhase() == GamePhase.VOTE_SKIPPED) {
            scheduleVoteSkippedExpiration(code, room.getCurrentRound().getVoteSkippedDeadline());
            return RoomMessage.voteSkippedSnapshot(VoteSkippedSnapshot.from(room));
        }

        if (room.hasAllCurrentRoundVotes()) {
            fromPhase = room.getPhase();
            room.completeVoting(completedAt, resultDuration);
            GamePhaseService.logPhaseTransition(code, fromPhase, room.getPhase());
            scheduleResultExpiration(code, room.getResultDeadline());
            return RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room));
        }
        scheduleVoteExpiration(code, room.getVoteDeadline());
        sendOwnVoteOptions(code, room);
        return RoomMessage.voteSnapshot(VoteSnapshot.from(room));
    }

    private void sendOwnVoteOptions(String code, GameRoom room) {
        int roundNumber = room.getCurrentRound().getRoundNumber();
        room.getCurrentRoundOwnVoteOptions().forEach((playerId, ownVoteOption) ->
                eventPublisher.sendOwnVoteOption(playerId, OwnVoteOptionNotice.of(code, roundNumber, ownVoteOption)));
    }

    void scheduleVoteExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVote(code, deadline, () -> runVoteExpiration(code, deadline));
    }

    private void runVoteExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isVoteExpirationStale(deadline)) {
                        return null;
                    }
                    GamePhase fromPhase = lockedRoom.getPhase();
                    lockedRoom.completeVoting(Instant.now(), resultDuration);
                    GamePhaseService.logPhaseTransition(code, fromPhase, lockedRoom.getPhase());
                    scheduleResultExpiration(code, lockedRoom.getResultDeadline());
                    return RoundResultSnapshot.from(lockedRoom);
                })
                .ifPresent(snapshot -> eventPublisher.publishRoundResult(code, snapshot));
    }

    void scheduleVoteSkippedExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVoteSkipped(code, deadline, () -> runVoteSkippedExpiration(code, deadline));
    }

    private void runVoteSkippedExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isVoteSkippedExpirationStale(deadline)) {
                        return null;
                    }

                    Instant expiredAt = Instant.now();
                    GamePhase fromPhase = lockedRoom.getPhase();
                    lockedRoom.completeVoteSkipped(expiredAt, resultDuration);
                    GamePhaseService.logPhaseTransition(code, fromPhase, lockedRoom.getPhase());
                    scheduleResultExpiration(code, lockedRoom.getResultDeadline());

                    return RoundResultSnapshot.from(lockedRoom);
                })
                .ifPresent(snapshot -> eventPublisher.publishRoundResult(code, snapshot));
    }

    void scheduleResultExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleResult(code, deadline, () -> runResultExpiration(code, deadline));
    }

    void runResultExpiration(String code, Instant deadline) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isResultExpirationStale(deadline)) {
                        return null;
                    }
                    return advanceRoundAndPrepare(code, lockedRoom);
                })
                .ifPresent(result -> {
                    eventPublisher.publish(code, result.message());
                    if (result.message().type() == RoomMessageType.GAME_RESULT_SNAPSHOT) {
                        gameRoomRepository.remove(result.room());
                        gameDrainLifecycle.onGameEnded(code);
                    }
                });
    }

    private RoundAdvanceResult advanceRoundAndPrepare(String code, GameRoom room) {
        GamePhase fromPhase = room.getPhase();
        room.advanceRound(Instant.now(), guessDuration);
        GamePhaseService.logPhaseTransition(code, fromPhase, room.getPhase());
        if (room.getPhase() == GamePhase.ENDED) {
            return new RoundAdvanceResult(room, RoomMessage.gameResultSnapshot(GameResultSnapshot.from(room)));
        }
        guessPhaseService.scheduleGuessExpiration(
                code,
                room.getFinalGuessSubmissionDeadline(),
                this::completeGuessSubmission);
        return new RoundAdvanceResult(room, RoomMessage.roundSnapshot(RoundSnapshot.from(room)));
    }

    private record RoundAdvanceResult(GameRoom room, RoomMessage<?> message) {
    }

}
