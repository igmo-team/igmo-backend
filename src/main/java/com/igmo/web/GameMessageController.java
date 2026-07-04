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
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        String playerId = sessionAttributes == null ? null
                : (String) sessionAttributes.get(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE);
        if (playerId == null) {
            // CONNECT 시 secret 검증을 통과하지 못한 위조/비정상 연결이므로 무시한다.
            log.warn("준비 상태 변경 요청에 세션 playerId가 없어 무시한다. code={}", code);
            return;
        }
        gameService.changeReady(code, playerId, request.ready());
    }

    @MessageExceptionHandler
    public void handleException(Exception ex) {
        log.warn("준비 상태 변경 처리에 실패했다.", ex);
    }
}
