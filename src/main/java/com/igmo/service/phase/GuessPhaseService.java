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
import com.igmo.store.GameRoomRepository;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.snapshot.GuessSubmissionSnapshot;
import com.igmo.web.websocket.snapshot.RoundSnapshot;
import java.time.Instant;
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
        GuessSubmissionPublication result = gameRoomRepository.update(
                code,
                room -> createGuessSubmissionPublication(
                        code,
                        room,
                        playerId,
                        guess,
                        submissionType,
                        completeGuessSubmission));
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
                        room.getPhase());
            }
            snapshot = GuessSubmissionSnapshot.submitted(room, guess);
        } catch (DuplicateGuessSubmissionException
                 | GuessMatchesOthersException
                 | PerfectGuessAlreadyConfirmedException exception) {
            return new GuessSubmissionPublication(
                    GuessSubmissionSnapshot.rejected(room, guess, exception.getMessage()),
                    null,
                    room.getPhase());
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
                    room.getPhase());
        }
        return new GuessSubmissionPublication(
                snapshot,
                RoomMessage.roundSnapshot(RoundSnapshot.from(room)),
                room.getPhase());
    }

    private void publishGuessSubmission(String code, String playerId, GuessSubmissionPublication result) {
        if (result == null) {
            return;
        }
        if (result.hasRoomMessage()) {
            eventPublisher.publish(code, result.roomMessage());
        }
        eventPublisher.sendGuessSubmission(playerId, result.phase(), result.snapshot());
    }

    private record GuessSubmissionPublication(
            GuessSubmissionSnapshot snapshot,
            RoomMessage<?> roomMessage,
            GamePhase phase
    ) {
        private boolean hasRoomMessage() {
            return roomMessage != null;
        }
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
                        return null;
                    }
                    Instant expiredAt = Instant.now();
                    if (!lockedRoom.isFinalGuessSubmissionExpired(expiredAt)) {
                        scheduleGuessExpiration(
                                code,
                                lockedRoom.getFinalGuessSubmissionDeadline(),
                                completeGuessSubmission);
                        return null;
                    }
                    lockedRoom.autoSubmitGuesses(expiredAt);
                    return completeGuessSubmission.apply(lockedRoom, expiredAt);
                })
                .ifPresent(message -> eventPublisher.publish(code, message));
    }

}
