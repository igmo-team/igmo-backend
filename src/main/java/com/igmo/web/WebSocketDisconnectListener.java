package com.igmo.web;

import java.util.Map;

import com.igmo.service.PlayerPresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class WebSocketDisconnectListener {

    private final PlayerPresenceService playerPresenceService;

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
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
        playerPresenceService.handleDisconnect(roomCode, playerId);
    }
}
