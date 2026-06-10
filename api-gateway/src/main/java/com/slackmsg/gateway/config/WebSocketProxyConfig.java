package com.slackmsg.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxies WebSocket connections from :8080/ws to ws-gateway :8085/ws.
 * Transparent — client doesn't know it's going through a gateway.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@Slf4j
public class WebSocketProxyConfig implements WebSocketConfigurer {

    private final ServiceRoutes routes;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new WsProxyHandler(routes), "/ws").setAllowedOrigins("*");
    }

    @Slf4j
    static class WsProxyHandler extends TextWebSocketHandler {

        private final ServiceRoutes routes;
        private final ConcurrentHashMap<String, WebSocketSession> backendSessions = new ConcurrentHashMap<>();

        WsProxyHandler(ServiceRoutes routes) {
            this.routes = routes;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
            // Forward to ws-gateway with same query params (token)
            URI clientUri = clientSession.getUri();
            String query = clientUri != null && clientUri.getQuery() != null ? "?" + clientUri.getQuery() : "";
            String backendUrl = routes.getWsUrl().replace("http://", "ws://") + "/ws" + query;

            log.debug("WS Proxy: connecting to backend {}", backendUrl);

            StandardWebSocketClient wsClient = new StandardWebSocketClient();
            WebSocketSession backendSession = wsClient.doHandshake(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                    // Forward backend → client
                    try {
                        if (clientSession.isOpen()) {
                            clientSession.sendMessage(message);
                        }
                    } catch (IOException e) {
                        log.error("WS proxy backend→client error: {}", e.getMessage());
                    }
                }

                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                    try { if (clientSession.isOpen()) clientSession.close(status); } catch (Exception e) {}
                }
            }, null, URI.create(backendUrl)).get();

            backendSessions.put(clientSession.getId(), backendSession);
            log.info("WS Proxy: client {} → backend connected", clientSession.getId());
        }

        @Override
        protected void handleTextMessage(WebSocketSession clientSession, TextMessage message) throws Exception {
            // Forward client → backend
            WebSocketSession backendSession = backendSessions.get(clientSession.getId());
            if (backendSession != null && backendSession.isOpen()) {
                backendSession.sendMessage(message);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession clientSession, CloseStatus status) {
            WebSocketSession backendSession = backendSessions.remove(clientSession.getId());
            if (backendSession != null) {
                try { backendSession.close(status); } catch (Exception e) {}
            }
            log.info("WS Proxy: client {} disconnected", clientSession.getId());
        }
    }
}
