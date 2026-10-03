package com.igmo.service.phase;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.service.GameDrainLifecycle;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.message.RoomMessageType;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import com.igmo.web.websocket.snapshot.RoundResultSnapshot;
import com.igmo.web.websocket.snapshot.RoundSnapshot;
import com.igmo.web.websocket.snapshot.VoteSkippedSnapshot;
import com.igmo.web.websocket.snapshot.VoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        Instant submittedAt = Instant.now();
        VoteSubmissionPublication publication = gameRoomRepository.updateWithCas(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            GamePhase fromPhase = room.getPhase();
            room.submitVote(playerId, optionId, submittedAt);
            if (room.hasAllCurrentRoundVotes()) {
                room.completeVoting(submittedAt, resultDuration);
                return RoomUpdate.changed(
                        new VoteSubmissionPublication(
                                RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room)),
                                fromPhase,
                                room.getPhase(),
                                true,
                                room.getResultDeadline()),
                        List.of(GameRoomDelivery.roomSnapshot()));
            }
            return RoomUpdate.changed(
                    new VoteSubmissionPublication(
                            RoomMessage.voteSnapshot(VoteSnapshot.from(room)),
                            fromPhase,
                            room.getPhase(),
                            false,
                            null),
                    List.of(GameRoomDelivery.roomSnapshot()));
        }).value();
        GamePhaseService.logPhaseTransition(code, publication.fromPhase(), publication.toPhase());
        if (publication.cancelVoteTimer()) {
            gamePhaseScheduler.cancelVote(code);
            scheduleResultExpiration(code, publication.resultDeadline());
        }
        eventPublisher.publish(code, publication.message());
    }

    GuessCompletion completeGuessSubmission(GameRoom room, Instant completedAt) {
        String code = room.getCode();
        GamePhase fromPhase = room.getPhase();
        room.completeGuessSubmission(completedAt, voteDuration, voteSkippedDuration);

        if (room.getPhase() == GamePhase.VOTE_SKIPPED) {
            Instant deadline = room.getCurrentRound().getVoteSkippedDeadline();
            return new GuessCompletion(
                    RoomMessage.voteSkippedSnapshot(VoteSkippedSnapshot.from(room)),
                    fromPhase,
                    room.getPhase(),
                    () -> scheduleVoteSkippedExpiration(code, deadline));
        }

        if (room.hasAllCurrentRoundVotes()) {
            room.completeVoting(completedAt, resultDuration);
            Instant deadline = room.getResultDeadline();
            return new GuessCompletion(
                    RoomMessage.roundResultSnapshot(RoundResultSnapshot.from(room)),
                    fromPhase,
                    room.getPhase(),
                    () -> scheduleResultExpiration(code, deadline));
        }
        Instant deadline = room.getVoteDeadline();
        return new GuessCompletion(
                RoomMessage.voteSnapshot(VoteSnapshot.from(room)),
                fromPhase,
                room.getPhase(),
                () -> scheduleVoteExpiration(code, deadline));
    }

    void scheduleVoteExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVote(code, deadline, () -> runVoteExpiration(code, deadline));
    }

    private void runVoteExpiration(String code, Instant deadline) {
        Instant expiredAt = Instant.now();
        Optional<RoomUpdate<VoteExpirationPublication>> update = gameRoomRepository.updateIfPresentWithCas(
                code,
                lockedRoom -> {
                    if (lockedRoom.isVoteExpirationStale(deadline)) {
                        return RoomUpdate.unchanged(null);
                    }
                    GamePhase fromPhase = lockedRoom.getPhase();
                    lockedRoom.completeVoting(expiredAt, resultDuration);
                    VoteExpirationPublication publication = new VoteExpirationPublication(
                            RoundResultSnapshot.from(lockedRoom),
                            fromPhase,
                            lockedRoom.getPhase(),
                            lockedRoom.getResultDeadline());
                    return lockedRoom.getPhase() == fromPhase
                            ? RoomUpdate.unchanged(publication)
                            : RoomUpdate.changed(publication, List.of(GameRoomDelivery.roomSnapshot()));
                });
        update.filter(roomUpdate -> roomUpdate.changed()).map(RoomUpdate::value).ifPresent(publication -> {
            GamePhaseService.logPhaseTransition(code, publication.fromPhase(), publication.toPhase());
            if (publication.resultDeadline() != null) {
                scheduleResultExpiration(code, publication.resultDeadline());
            }
            eventPublisher.publishRoundResult(code, publication.snapshot());
        });
    }

    void scheduleVoteSkippedExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleVoteSkipped(code, deadline, () -> runVoteSkippedExpiration(code, deadline));
    }

    private void runVoteSkippedExpiration(String code, Instant deadline) {
        Instant expiredAt = Instant.now();
        Optional<RoomUpdate<VoteExpirationPublication>> update = gameRoomRepository.updateIfPresentWithCas(
                code,
                lockedRoom -> {
                    if (lockedRoom.isVoteSkippedExpirationStale(deadline)) {
                        return RoomUpdate.unchanged(null);
                    }

                    GamePhase fromPhase = lockedRoom.getPhase();
                    lockedRoom.completeVoteSkipped(expiredAt, resultDuration);
                    VoteExpirationPublication publication = new VoteExpirationPublication(
                            RoundResultSnapshot.from(lockedRoom),
                            fromPhase,
                            lockedRoom.getPhase(),
                            lockedRoom.getResultDeadline());
                    return RoomUpdate.changed(publication, List.of(GameRoomDelivery.roomSnapshot()));
                });
        update.filter(roomUpdate -> roomUpdate.changed()).map(RoomUpdate::value).ifPresent(publication -> {
            GamePhaseService.logPhaseTransition(code, publication.fromPhase(), publication.toPhase());
            if (publication.resultDeadline() != null) {
                scheduleResultExpiration(code, publication.resultDeadline());
            }
            eventPublisher.publishRoundResult(code, publication.snapshot());
        });
    }

    void scheduleResultExpiration(String code, Instant deadline) {
        gamePhaseScheduler.scheduleResult(code, deadline, () -> runResultExpiration(code, deadline));
    }

    void runResultExpiration(String code, Instant deadline) {
        Instant advancedAt = Instant.now();
        Optional<RoomUpdate<RoundAdvanceResult>> update = gameRoomRepository.updateIfPresentWithCas(
                code,
                lockedRoom -> {
                    if (lockedRoom.isResultExpirationStale(deadline)) {
                        return RoomUpdate.unchanged(null);
                    }
                    RoundAdvanceResult result = advanceRoundAndPrepare(lockedRoom, advancedAt);
                    if (result.ended()) {
                        return RoomUpdate.deleted(result, deliveriesFor(result));
                    }
                    return RoomUpdate.changed(result, deliveriesFor(result));
                });
        update.filter(roomUpdate -> roomUpdate.changed()).map(RoomUpdate::value).ifPresent(result -> {
            GamePhaseService.logPhaseTransition(code, result.fromPhase(), result.room().getPhase());
            if (result.guessDeadline() != null) {
                guessPhaseService.scheduleGuessExpiration(
                        code,
                        result.guessDeadline(),
                        this::completeGuessSubmission);
            }
            eventPublisher.publish(code, result.message());
            if (result.ended()) {
                gameDrainLifecycle.onGameEnded(code);
            }
        });
    }

    private RoundAdvanceResult advanceRoundAndPrepare(GameRoom room, Instant advancedAt) {
        GamePhase fromPhase = room.getPhase();
        room.advanceRound(advancedAt, guessDuration);
        if (room.getPhase() == GamePhase.ENDED) {
            return new RoundAdvanceResult(
                    room,
                    fromPhase,
                    RoomMessage.gameResultSnapshot(GameResultSnapshot.from(room)),
                    null);
        }
        return new RoundAdvanceResult(
                room,
                fromPhase,
                RoomMessage.roundSnapshot(RoundSnapshot.from(room)),
                room.getFinalGuessSubmissionDeadline());
    }

    private List<GameRoomDelivery> deliveriesFor(RoundAdvanceResult result) {
        if (result.ended()) {
            return List.of(GameRoomDelivery.gameResult(GameResultSnapshot.from(result.room())));
        }
        return List.of(GameRoomDelivery.roomSnapshot());
    }

    private record VoteSubmissionPublication(
            RoomMessage<?> message,
            GamePhase fromPhase,
            GamePhase toPhase,
            boolean cancelVoteTimer,
            Instant resultDeadline
    ) {
    }

    private record VoteExpirationPublication(
            RoundResultSnapshot snapshot,
            GamePhase fromPhase,
            GamePhase toPhase,
            Instant resultDeadline
    ) {
    }

    private record RoundAdvanceResult(
            GameRoom room,
            GamePhase fromPhase,
            RoomMessage<?> message,
            Instant guessDeadline
    ) {
        private boolean ended() {
            return message.type() == RoomMessageType.GAME_RESULT_SNAPSHOT;
        }
    }

    record GuessCompletion(
            RoomMessage<?> message,
            GamePhase fromPhase,
            GamePhase toPhase,
            Runnable scheduleNextTimer
    ) {
    }
}
