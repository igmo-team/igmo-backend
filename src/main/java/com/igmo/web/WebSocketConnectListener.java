package com.igmo.web;

import com.igmo.service.GameService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;

@Component
@RequiredArgsConstructor
public class WebSocketConnectListener {

    private final GameService gameService;

    @EventListener
    public void handleSessionConnect(SessionConnectEvent event) {
        Map<String, Object> sessionAttributes =
                SimpMessageHeaderAccessor.getSessionAttributes(event.getMessage().getHeaders());
        if (sessionAttributes == null) {
            return;
        }
        String roomCode = (String) sessionAttributes.get(PlayerSessionInterceptor.ROOM_CODE_ATTRIBUTE);
        String playerId = (String) sessionAttributes.get(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE);
        if (roomCode == null || playerId == null) {
            return;
        }
        gameService.cancelPendingRemoval(roomCode, playerId);
    }
}
