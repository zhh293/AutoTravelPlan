package com.zhh.handsome.gaodemcptest.config;

import com.zhh.handsome.gaodemcptest.websocket.TravelPlanWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TravelPlanWebSocketHandler travelPlanWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(travelPlanWebSocketHandler, "/ws/travel-plan")
                .setAllowedOrigins("*");
    }
}

