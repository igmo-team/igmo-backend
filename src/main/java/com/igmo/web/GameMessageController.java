package com.igmo.web;

import com.igmo.service.GameService;
import com.igmo.web.dto.PromptRequest;
import com.igmo.web.dto.ReadyRequest;
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

    private final GameService gameService;
    private final PlayerSessionResolver playerSessionResolver;

    @MessageMapping("/rooms/{code}/ready")
    public void changeReady(@DestinationVariable String code,
                            ReadyRequest request,
                            SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gameService.changeReady(code, playerId, request.ready());
    }

    @MessageMapping("/rooms/{code}/start")
    public void startGame(@DestinationVariable String code,
                          SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gameService.startGame(code, playerId);
    }

    @MessageMapping("/rooms/{code}/prompts")
    public void submitPrompt(@DestinationVariable String code,
                             @Valid PromptRequest request,
                             SimpMessageHeaderAccessor headerAccessor) {
        String playerId = requirePlayerId(headerAccessor);
        gameService.submitPrompt(code, playerId, request.prompt());
    }

    private String requirePlayerId(SimpMessageHeaderAccessor headerAccessor) {
        String playerId = playerSessionResolver.resolvePlayerId(headerAccessor);
        if (playerId == null) {
            throw new PlayerSessionNotFoundException();
        }
        return playerId;
    }
}
