package com.igmo.web;

import com.igmo.service.GameLobbyService;
import com.igmo.service.GamePhaseService;
import com.igmo.web.dto.GuessRequest;
import com.igmo.web.dto.PromptRequest;
import com.igmo.web.dto.ReadyRequest;
import com.igmo.web.dto.VoteRequest;
import com.igmo.web.exception.PlayerSessionNotFoundException;
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

    @MessageMapping("/rooms/{code}/prompts")
    public void submitPrompt(@DestinationVariable String code,
                             @Valid PromptRequest request,
                             SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gamePhaseService.submitPrompt(code, playerId, request.prompt());
    }

    @MessageMapping("/rooms/{code}/guesses")
    public void submitGuess(@DestinationVariable String code,
                            @Valid GuessRequest request,
                            SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gamePhaseService.submitGuess(code, playerId, request.guess());
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
