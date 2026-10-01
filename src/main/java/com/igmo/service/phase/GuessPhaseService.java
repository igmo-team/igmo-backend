package com.igmo.service.phase;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GuessSubmissionResult;
import com.igmo.domain.GuessSubmissionType;
import com.igmo.domain.exception.DuplicateGuessSubmissionException;
import com.igmo.domain.exception.GuessMatchesOthersException;
import com.igmo.domain.exception.PerfectGuessAlreadyConfirmedException;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import com.igmo.web.websocket.message.OwnVoteOptionNotice;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.snapshot.GuessSubmissionSnapshot;
import com.igmo.web.websocket.snapshot.RoundSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GuessPhaseService {

    private final GameRoomRepository gameRoomRepository;
    private final GamePhaseScheduler gamePhaseScheduler;
    private final GameEventPublisher eventPublisher;

    public void submitGuess(
            String code,
            String playerId,
            String guess,
            GuessSubmissionType submissionType,
            BiFunction<GameRoom, Instant, RoomMessage<?>> completeGuessSubmission
    ) {
        GuessSubmissionPublication result = gameRoomRepository.updateWithDeliveries(
                code,
                room -> {
                    GuessSubmissionPublication publication = createGuessSubmissionPublication(
                            code,
                            room,
                            playerId,
                            guess,
                            submissionType,
                            completeGuessSubmission);
                    if (publication == null || !publication.changed()) {
                        return RoomUpdate.unchanged(publication);
                    }
                    return RoomUpdate.changed(
                            publication,
                            deliveriesFor(publication.roomMessage(), publication.ownVoteOptions()));
                });
        publishGuessSubmission(code, playerId, result);
    }

    private GuessSubmissionPublication createGuessSubmissionPublication(
            String code,
            GameRoom room,
            String playerId,
            String guess,
            GuessSubmissionType submissionType,
            BiFunction<GameRoom, Instant, RoomMessage<?>> completeGuessSubmission
    ) {
        if (!room.hasPlayer(playerId)) {
            throw new PlayerNotFoundException();
        }
        Instant submittedAt = Instant.now();
        GuessSubmissionResult guessSubmissionResult;
        GuessSubmissionSnapshot snapshot;
        try {
            guessSubmissionResult = room.submitGuess(playerId, guess, submittedAt, submissionType);
            if (guessSubmissionResult == GuessSubmissionResult.NOT_SUBMITTED) {
                return null;
            }
            if (guessSubmissionResult == GuessSubmissionResult.PERFECT_RETRY_REQUIRED) {
                return new GuessSubmissionPublication(
                        GuessSubmissionSnapshot.perfect(room, guess),
                        null,
                        room.getPhase(),
                        List.of(),
                        true);
            }
            snapshot = GuessSubmissionSnapshot.submitted(room, guess);
        } catch (DuplicateGuessSubmissionException
                 | GuessMatchesOthersException
                 | PerfectGuessAlreadyConfirmedException exception) {
            return new GuessSubmissionPublication(
                    GuessSubmissionSnapshot.rejected(room, guess, exception.getMessage()),
                    null,
                    room.getPhase(),
                    List.of(),
                    false);
        }
        return createPublicationAfterSuccessfulGuess(
                code, room, snapshot, submittedAt, completeGuessSubmission);
    }

    private GuessSubmissionPublication createPublicationAfterSuccessfulGuess(
            String code,
            GameRoom room,
            GuessSubmissionSnapshot snapshot,
            Instant submittedAt,
            BiFunction<GameRoom, Instant, RoomMessage<?>> completeGuessSubmission
    ) {
        if (room.hasAllCurrentRoundGuesses()) {
            gamePhaseScheduler.cancelGuess(code);
            return new GuessSubmissionPublication(
                    snapshot,
                    completeGuessSubmission.apply(room, submittedAt),
                    room.getPhase(),
                    ownVoteOptions(room),
                    true);
        }
        return new GuessSubmissionPublication(
                snapshot,
                RoomMessage.roundSnapshot(RoundSnapshot.from(room)),
                room.getPhase(),
                List.of(),
                true);
    }

    private void publishGuessSubmission(String code, String playerId, GuessSubmissionPublication result) {
        if (result == null) {
            return;
        }
        result.ownVoteOptions()
                .forEach(publication -> eventPublisher.sendOwnVoteOption(
                                publication.playerId(),
                                publication.notice()
                        )
                );
        if (result.hasRoomMessage()) {
            eventPublisher.publish(code, result.roomMessage());
        }
        eventPublisher.sendGuessSubmission(playerId, result.phase(), result.snapshot());
    }

    private record GuessSubmissionPublication(
            GuessSubmissionSnapshot snapshot,
            RoomMessage<?> roomMessage,
            GamePhase phase,
            List<OwnVoteOptionPublication> ownVoteOptions,
            boolean changed
    ) {
        private boolean hasRoomMessage() {
            return roomMessage != null;
        }
    }

    private List<OwnVoteOptionPublication> ownVoteOptions(GameRoom room) {
        if (room.getPhase() != GamePhase.VOTING) {
            return List.of();
        }
        int roundNumber = room.getCurrentRound().getRoundNumber();
        return room.getCurrentRoundOwnVoteOptions().entrySet().stream()
                .map(entry -> new OwnVoteOptionPublication(
                        entry.getKey(),
                        OwnVoteOptionNotice.of(room.getCode(), roundNumber, entry.getValue())))
                .toList();
    }

    private List<GameRoomDelivery> deliveriesFor(
            RoomMessage<?> roomMessage,
            List<OwnVoteOptionPublication> ownVoteOptions
    ) {
        if (roomMessage == null) {
            return List.of();
        }
        if (ownVoteOptions.isEmpty()) {
            return List.of(GameRoomDelivery.roomSnapshot());
        }
        return List.of(GameRoomDelivery.ownVoteOptions(), GameRoomDelivery.roomSnapshot());
    }

    private record OwnVoteOptionPublication(String playerId, OwnVoteOptionNotice notice) {
    }

    void scheduleGuessExpiration(
            String code,
            Instant deadline,
            BiFunction<GameRoom, Instant, RoomMessage<?>> completeGuessSubmission
    ) {
        gamePhaseScheduler.scheduleGuess(
                code,
                deadline,
                () -> runGuessExpiration(code, deadline, completeGuessSubmission));
    }

    void runGuessExpiration(
            String code,
            Instant deadline,
            BiFunction<GameRoom, Instant, RoomMessage<?>> completeGuessSubmission
    ) {
        gameRoomRepository.updateIfPresent(code, lockedRoom -> {
                    if (lockedRoom.isGuessExpirationStale(deadline)) {
                        return RoomUpdate.unchanged(null);
                    }
                    Instant expiredAt = Instant.now();
                    if (!lockedRoom.isFinalGuessSubmissionExpired(expiredAt)) {
                        scheduleGuessExpiration(
                                code,
                                lockedRoom.getFinalGuessSubmissionDeadline(),
                                completeGuessSubmission);
                        return RoomUpdate.unchanged(null);
                    }
                    lockedRoom.autoSubmitGuesses(expiredAt);
                    GamePhase fromPhase = lockedRoom.getPhase();
                    RoomMessage<?> message = completeGuessSubmission.apply(lockedRoom, expiredAt);
                    GuessExpirationPublication publication = new GuessExpirationPublication(
                            message,
                            ownVoteOptions(lockedRoom)
                    );
                    return lockedRoom.getPhase() == fromPhase
                            ? RoomUpdate.unchanged(publication)
                            : RoomUpdate.changed(
                                    publication,
                                    deliveriesFor(publication.roomMessage(), publication.ownVoteOptions()));
                })
                .ifPresent(publication -> {
                    publication.ownVoteOptions()
                            .forEach(option -> eventPublisher.sendOwnVoteOption(
                                            option.playerId(),
                                            option.notice()
                                    )
                            );
                    eventPublisher.publish(code, publication.roomMessage());
                });
    }

    private record GuessExpirationPublication(
            RoomMessage<?> roomMessage,
            List<OwnVoteOptionPublication> ownVoteOptions
    ) {
    }

}
