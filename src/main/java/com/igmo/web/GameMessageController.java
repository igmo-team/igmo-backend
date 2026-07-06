package com.igmo.web;

import com.igmo.service.GameService;
import com.igmo.web.dto.ReadyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class GameMessageController {

    private final GameService gameService;
    private final PlayerSessionResolver playerSessionResolver;

    @MessageMapping("/rooms/{code}/ready")
    public void changeReady(@DestinationVariable String code,
                            ReadyRequest request,
                            SimpMessageHeaderAccessor headerAccessor) {
        String playerId = playerSessionResolver.resolvePlayerId(headerAccessor);
        if (playerId == null) {
            log.warn("준비 상태 변경 요청에 세션 playerId가 없어 무시한다. code={}", code);
            return;
        }
        gameService.changeReady(code, playerId, request.ready());
    }

    @MessageMapping("/rooms/{code}/start")
    public void startGame(@DestinationVariable String code,
                          SimpMessageHeaderAccessor headerAccessor) {
        String playerId = playerSessionResolver.resolvePlayerId(headerAccessor);
        if (playerId == null) {
            log.warn("게임 시작 요청에 세션 playerId가 없어 무시한다. code={}", code);
            return;
        }
        gameService.startGame(code, playerId);
    }

    @MessageExceptionHandler
    public void handleException(Exception ex) {
        log.warn("게임 메시지 처리에 실패했다.", ex);
    }
}
