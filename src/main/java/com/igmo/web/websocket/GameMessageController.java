package com.igmo.web.websocket;

import com.igmo.service.GameRoomStateSyncService;
import com.igmo.service.lobby.GameLobbyService;
import com.igmo.service.phase.GamePhaseService;
import com.igmo.web.websocket.exception.PlayerSessionNotFoundException;
import com.igmo.web.websocket.request.GuessRequest;
import com.igmo.web.websocket.request.PromptRequest;
import com.igmo.web.websocket.request.ReadyRequest;
import com.igmo.web.websocket.request.VoteRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class GameMessageController {

    private final GameLobbyService gameLobbyService;
    private final GamePhaseService gamePhaseService;
    private final GameRoomStateSyncService gameRoomStateSyncService;
    private final PlayerSessionResolver playerSessionResolver;

    @MessageMapping("/rooms/{code}/ready")
    public void changeReady(@DestinationVariable String code,
                            ReadyRequest request,
                            SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gameLobbyService.changeReady(code, playerId, request.ready());
    }

    @MessageMapping("/rooms/{code}/start")
    public void startGame(@DestinationVariable String code,
                          SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gamePhaseService.startGame(code, playerId);
    }

    @MessageMapping("/rooms/{code}/sync")
    public void sync(@DestinationVariable String code,
                     SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gameRoomStateSyncService.sync(code, playerId);
    }

    @MessageMapping("/rooms/{code}/prompts")
    public void submitPrompt(@DestinationVariable String code,
                             @Valid PromptRequest request,
                             SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gamePhaseService.submitPrompt(code, playerId, request.prompt(), request.submissionType());
    }

    @MessageMapping("/rooms/{code}/guesses")
    public void submitGuess(@DestinationVariable String code,
                            @Valid GuessRequest request,
                            SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gamePhaseService.submitGuess(code, playerId, request.guess(), request.submissionType());
    }

    @MessageMapping("/rooms/{code}/votes")
    public void submitVote(@DestinationVariable String code,
                           @Valid VoteRequest request,
                           SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gamePhaseService.submitVote(code, playerId, request.optionId());
    }

    private String requirePlayerId(SimpMessageHeaderAccessor headerAccessor) {
        String playerId = playerSessionResolver.resolvePlayerId(headerAccessor);
        if (playerId == null) {
            throw new PlayerSessionNotFoundException();
        }
        return playerId;
    }
}
