package com.zhh.handsome.gaodemcptest.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class TravelPlanWebSocketSender {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Set<WebSocketSession>> sessionsByTaskId = new ConcurrentHashMap<>();

    public void registerSession(String taskId, WebSocketSession session) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        sessionsByTaskId.compute(taskId, (k, v) -> {
            Set<WebSocketSession> set = v;
            if (set == null) {
                set = Collections.newSetFromMap(new ConcurrentHashMap<>());
            }
            set.add(session);
            return set;
        });
    }

    public void removeSession(WebSocketSession session) {
        sessionsByTaskId.forEach((taskId, set) -> set.remove(session));
    }

    public void sendPayload(String taskId, Object payload) {
        send(taskId, Map.of("taskId", taskId, "success", true, "type", "travel-plan", "data", payload));
    }

    public void sendError(String taskId, String errorMessage) {
        send(taskId, Map.of("taskId", taskId, "success", false, "type", "error", "message", errorMessage));
    }

    private void send(String taskId, Object body) {
        Set<WebSocketSession> sessions = sessionsByTaskId.get(taskId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            TextMessage msg = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(msg);
                    } catch (IOException e) {
                        log.error("WebSocket发送失败, taskId={}", taskId, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("序列化WebSocket消息失败, taskId={}", taskId, e);
        }
    }
}

