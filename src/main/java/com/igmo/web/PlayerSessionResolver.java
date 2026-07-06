package com.igmo.web;

import java.util.Map;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class PlayerSessionResolver {

    public String resolvePlayerId(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        return (String) sessionAttributes.get(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE);
    }
}
