package com.igmo.web;

import java.util.Map;

import com.igmo.service.GameService;
import com.igmo.web.dto.ReadyRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class GameMessageController {

    private static final Logger log = LoggerFactory.getLogger(GameMessageController.class);

    private final GameService gameService;

    @MessageMapping("/rooms/{code}/ready")
    public void changeReady(@DestinationVariable String code,
                            ReadyRequest request,
                            SimpMessageHeaderAccessor headerAccessor) {
        String playerId = resolvePlayerId(headerAccessor);
        if (playerId == null) {
            log.warn("준비 상태 변경 요청에 세션 playerId가 없어 무시한다. code={}", code);
            return;
        }
        gameService.changeReady(code, playerId, request.ready());
    }

    @MessageMapping("/rooms/{code}/start")
    public void startGame(@DestinationVariable String code,
                          SimpMessageHeaderAccessor headerAccessor) {
        String playerId = resolvePlayerId(headerAccessor);
        if (playerId == null) {
            log.warn("게임 시작 요청에 세션 playerId가 없어 무시한다. code={}", code);
            return;
        }
        gameService.startGame(code, playerId);
    }

    // CONNECT 시 secret 검증을 통과한 연결만 세션에 playerId가 저장되어 있다. 없으면 위조/비정상 연결이다.
    private String resolvePlayerId(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        return (String) sessionAttributes.get(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE);
    }

    @MessageExceptionHandler
    public void handleException(Exception ex) {
        log.warn("게임 메시지 처리에 실패했다.", ex);
    }
}
