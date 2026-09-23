package com.igmo.web.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class WebSocketSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public int count() {
        sessions.entrySet().removeIf(entry -> !entry.getValue().isOpen());
        return sessions.size();
    }

    public void close(Set<String> sessionIds) {
        sessionIds.stream()
                .map(sessions::get)
                .filter(Objects::nonNull)
                .forEach(this::close);
    }

    public void closeAll() {
        sessions.values().forEach(this::close);
    }

    private void close(WebSocketSession session) {
        if (!session.isOpen()) {
            unregister(session.getId());
            return;
        }
        try {
            session.close(CloseStatus.GOING_AWAY);
        } catch (IOException exception) {
            log.warn("WebSocket 세션 종료에 실패했습니다. sessionId={}", session.getId(), exception);
        }
    }
}
