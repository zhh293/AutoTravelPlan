package com.zhh.handsome.gaodemcptest.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TravelPlanWebSocketHandler extends TextWebSocketHandler implements WebSocketHandler {

    private final TravelPlanWebSocketSender sender;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            closeQuietly(session);
            return;
        }
        Map<String, String> params = parseQueryParams(uri.getQuery());
        String taskId = params.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            closeQuietly(session);
            return;
        }
        sender.registerSession(taskId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sender.removeSession(session);
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }
}

