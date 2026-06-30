package com.igmo.web;

import com.igmo.service.GameService;
import com.igmo.web.dto.CreateGameRequest;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameRequest;
import com.igmo.web.dto.JoinGameResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/games")
public class GameController {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;

    public GameController(GameService gameService, SimpMessagingTemplate messagingTemplate) {
        this.gameService = gameService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping
    public ResponseEntity<CreateGameResponse> createGame(@Valid @RequestBody CreateGameRequest request) {
        CreateGameResponse response = gameService.createGame(request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{code}/players")
    public ResponseEntity<JoinGameResponse> joinGame(
            @PathVariable String code,
            @Valid @RequestBody JoinGameRequest request) {
        JoinGameResponse response = gameService.joinGame(code, request.nickname());
        messagingTemplate.convertAndSend("/topic/room/" + code, response.snapshot());
        return ResponseEntity.ok(response);
    }
}
