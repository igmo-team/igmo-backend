package com.igmo.service.phase;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GuessSubmissionType;
import com.igmo.domain.PromptSubmissionType;
import com.igmo.service.GameRoomRestoredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GamePhaseService {

    private final PromptPhaseService promptPhaseService;
    private final GuessPhaseService guessPhaseService;
    private final VoteResultPhaseService voteResultPhaseService;

    public void startGame(String code, String playerId) {
        promptPhaseService.startGame(code, playerId);
    }

    public void submitPrompt(String code, String playerId, String prompt, PromptSubmissionType submissionType) {
        promptPhaseService.submitPrompt(code, playerId, prompt, submissionType);
    }

    public void onPlayerRemoved(String code) {
        promptPhaseService.onPlayerRemoved(code);
    }

    public void submitGuess(String code, String playerId, String guess) {
        submitGuess(code, playerId, guess, GuessSubmissionType.NORMAL);
    }

    public void submitGuess(String code, String playerId, String guess, GuessSubmissionType submissionType) {
        guessPhaseService.submitGuess(
                code, playerId, guess, submissionType, voteResultPhaseService::completeGuessSubmission);
    }

    public void submitVote(String code, String playerId, String optionId) {
        voteResultPhaseService.submitVote(code, playerId, optionId);
    }

    @EventListener
    public void restorePhaseExpiration(GameRoomRestoredEvent event) {
        GameRoom room = event.room();
        switch (room.getPhase()) {
            case GENERATING -> promptPhaseService.schedulePromptExpiration(
                    room.getCode(), room.getFinalPromptSubmissionDeadline());
            case PLAYING -> guessPhaseService.scheduleGuessExpiration(
                    room.getCode(),
                    room.getFinalGuessSubmissionDeadline(),
                    voteResultPhaseService::completeGuessSubmission);
            case VOTING -> voteResultPhaseService.scheduleVoteExpiration(room.getCode(), room.getVoteDeadline());
            case VOTE_SKIPPED -> voteResultPhaseService.scheduleVoteSkippedExpiration(
                    room.getCode(), room.getCurrentRound().getVoteSkippedDeadline());
            case RESULTS -> voteResultPhaseService.scheduleResultExpiration(room.getCode(), room.getResultDeadline());
            case LOBBY, ENDED -> {
            }
        }
    }

    static void logPhaseTransition(String roomCode, GamePhase fromPhase, GamePhase toPhase) {
        if (fromPhase == toPhase) {
            return;
        }
        log.atInfo()
                .addKeyValue("event", "game_phase_transition_completed")
                .addKeyValue("roomCode", roomCode)
                .addKeyValue("fromPhase", fromPhase)
                .addKeyValue("toPhase", toPhase)
                .log();
    }
}
