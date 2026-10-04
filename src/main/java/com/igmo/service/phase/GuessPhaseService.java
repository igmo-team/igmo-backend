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
import java.util.Optional;
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
            BiFunction<GameRoom, Instant, VoteResultPhaseService.GuessCompletion> completeGuessSubmission
    ) {
        Instant submittedAt = Instant.now();
        RoomUpdate<GuessSubmissionPublication> update = gameRoomRepository.updateWithCas(
                code,
                room -> {
                    GuessSubmissionPublication publication = createGuessSubmissionPublication(
                            room,
                            playerId,
                            guess,
                            submissionType,
                            submittedAt,
                            completeGuessSubmission);
                    if (publication == null || !publication.changed()) {
                        return RoomUpdate.unchanged(publication);
                    }
                    return RoomUpdate.changed(
                            publication,
                            deliveriesFor(publication.roomMessage(), publication.ownVoteOptions()));
                });
        GuessSubmissionPublication result = update.value();
        if (update.changed() && result != null) {
            GamePhaseService.logPhaseTransition(code, result.fromPhase(), result.phase());
            if (result.cancelGuessTimer()) {
                gamePhaseScheduler.cancelGuess(code);
            }
            result.completion().ifPresent(completion -> completion.scheduleNextTimer().run());
        }
        publishGuessSubmission(code, playerId, result);
    }

    private GuessSubmissionPublication createGuessSubmissionPublication(
            GameRoom room,
            String playerId,
            String guess,
            GuessSubmissionType submissionType,
            Instant submittedAt,
            BiFunction<GameRoom, Instant, VoteResultPhaseService.GuessCompletion> completeGuessSubmission
    ) {
        if (!room.hasPlayer(playerId)) {
            throw new PlayerNotFoundException();
        }
        GamePhase fromPhase = room.getPhase();
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
                        true,
                        fromPhase,
                        false,
                        Optional.empty());
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
                    false,
                    fromPhase,
                    false,
                    Optional.empty());
        }
        return createPublicationAfterSuccessfulGuess(
                room, snapshot, submittedAt, fromPhase, completeGuessSubmission);
    }

    private GuessSubmissionPublication createPublicationAfterSuccessfulGuess(
            GameRoom room,
            GuessSubmissionSnapshot snapshot,
            Instant submittedAt,
            GamePhase fromPhase,
            BiFunction<GameRoom, Instant, VoteResultPhaseService.GuessCompletion> completeGuessSubmission
    ) {
        if (room.hasAllCurrentRoundGuesses()) {
            VoteResultPhaseService.GuessCompletion completion = completeGuessSubmission.apply(room, submittedAt);
            return new GuessSubmissionPublication(
                    snapshot,
                    completion.message(),
                    room.getPhase(),
                    ownVoteOptions(room),
                    true,
                    completion.fromPhase(),
                    true,
                    Optional.of(completion));
        }
        return new GuessSubmissionPublication(
                snapshot,
                RoomMessage.roundSnapshot(RoundSnapshot.from(room)),
                room.getPhase(),
                List.of(),
                true,
                fromPhase,
                false,
                Optional.empty());
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
            boolean changed,
            GamePhase fromPhase,
            boolean cancelGuessTimer,
            Optional<VoteResultPhaseService.GuessCompletion> completion
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
            BiFunction<GameRoom, Instant, VoteResultPhaseService.GuessCompletion> completeGuessSubmission
    ) {
        gamePhaseScheduler.scheduleGuess(
                code,
                deadline,
                () -> runGuessExpiration(code, deadline, completeGuessSubmission));
    }

    void runGuessExpiration(
            String code,
            Instant deadline,
            BiFunction<GameRoom, Instant, VoteResultPhaseService.GuessCompletion> completeGuessSubmission
    ) {
        Instant expiredAt = Instant.now();
        Optional<RoomUpdate<GuessExpirationPublication>> update = gameRoomRepository.updateIfPresentWithCas(
                code,
                lockedRoom -> {
                    if (lockedRoom.isGuessExpirationStale(deadline)) {
                        return RoomUpdate.unchanged(null);
                    }
                    if (!lockedRoom.isFinalGuessSubmissionExpired(expiredAt)) {
                        return RoomUpdate.unchanged(new GuessExpirationPublication(
                                null,
                                List.of(),
                                Optional.empty(),
                                lockedRoom.getFinalGuessSubmissionDeadline(),
                                false));
                    }
                    lockedRoom.autoSubmitGuesses(expiredAt);
                    VoteResultPhaseService.GuessCompletion completion =
                            completeGuessSubmission.apply(lockedRoom, expiredAt);
                    GuessExpirationPublication publication = new GuessExpirationPublication(
                            completion.message(),
                            ownVoteOptions(lockedRoom),
                            Optional.of(completion),
                            null,
                            true
                    );
                    return RoomUpdate.changed(
                            publication,
                            deliveriesFor(publication.roomMessage(), publication.ownVoteOptions()));
                });
        update.map(RoomUpdate::value).ifPresent(publication -> {
            if (publication.retryDeadline() != null) {
                scheduleGuessExpiration(code, publication.retryDeadline(), completeGuessSubmission);
            }
            if (publication.shouldPublish()) {
                publication.completion().ifPresent(completion -> {
                    GamePhaseService.logPhaseTransition(code, completion.fromPhase(), completion.toPhase());
                    completion.scheduleNextTimer().run();
                });
                publication.ownVoteOptions()
                        .forEach(option -> eventPublisher.sendOwnVoteOption(
                                        option.playerId(),
                                        option.notice()
                                )
                        );
                eventPublisher.publish(code, publication.roomMessage());
            }
        });
    }

    private record GuessExpirationPublication(
            RoomMessage<?> roomMessage,
            List<OwnVoteOptionPublication> ownVoteOptions,
            Optional<VoteResultPhaseService.GuessCompletion> completion,
            Instant retryDeadline,
            boolean shouldPublish
    ) {
    }

}
