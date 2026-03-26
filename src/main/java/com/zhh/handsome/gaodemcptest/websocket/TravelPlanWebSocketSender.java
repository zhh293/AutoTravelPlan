package com.zhh.handsome.gaodemcptest.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
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
    private final Map<String, Set<Channel>> channelsByTaskId = new ConcurrentHashMap<>();

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

    public void registerChannel(String taskId, Channel channel) {
        if (taskId == null || taskId.isBlank() || channel == null) {
            return;
        }
        channelsByTaskId.compute(taskId, (k, v) -> {
            Set<Channel> set = v;
            if (set == null) {
                set = Collections.newSetFromMap(new ConcurrentHashMap<>());
            }
            set.add(channel);
            return set;
        });
    }

    public void removeChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        channelsByTaskId.forEach((taskId, set) -> set.remove(channel));
    }

    public void sendPayload(String taskId, Object payload) {
        send(taskId, Map.of("taskId", taskId, "success", true, "type", "travel-plan", "data", payload));
    }

    public void sendError(String taskId, String errorMessage) {
        send(taskId, Map.of("taskId", taskId, "success", false, "type", "error", "message", errorMessage));
    }

    public void closeTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByTaskId.remove(taskId);
        if (sessions != null) {
            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        Set<Channel> channels = channelsByTaskId.remove(taskId);
        if (channels != null) {
            for (Channel channel : channels) {
                if (channel.isActive()) {
                    channel.close();
                }
            }
        }
    }

    private void send(String taskId, Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            TextMessage msg = new TextMessage(json);
            sendToSpringSessions(taskId, msg);
            sendToNettyChannels(taskId, json);
        } catch (Exception e) {
            log.error("序列化WebSocket消息失败, taskId={}", taskId, e);
        }
    }

    private void sendToSpringSessions(String taskId, TextMessage msg) {
        Set<WebSocketSession> sessions = sessionsByTaskId.get(taskId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(msg);
                } catch (IOException e) {
                    log.error("WebSocket发送失败, taskId={}", taskId, e);
                }
            }
        }
    }

    private void sendToNettyChannels(String taskId, String json) {
        Set<Channel> channels = channelsByTaskId.get(taskId);
        if (channels == null || channels.isEmpty()) {
            return;
        }
        TextWebSocketFrame frame = new TextWebSocketFrame(json);
        for (Channel channel : channels) {
            if (channel.isActive()) {
                channel.writeAndFlush(frame.retainedDuplicate());
            }
        }
    }
}
