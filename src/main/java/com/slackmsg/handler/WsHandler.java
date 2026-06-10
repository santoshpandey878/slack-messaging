package com.slackmsg.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.MessageServicePort;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.service.WsSessionManager;
import com.slackmsg.util.JwtUtil;
import com.slackmsg.util.WsPayloadBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket handler — thin orchestrator.
 * Delegates: session mgmt → WsSessionManager, message building → WsPayloadBuilder.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsHandler extends TextWebSocketHandler {

    private final JwtUtil jwtUtil;
    private final PubSubService pubSub;
    private final MessageServicePort messageService;
    private final ChannelServicePort channelService;
    private final ObjectMapper objectMapper;
    private final WsSessionManager sessionManager;

    private final AtomicBoolean serverSubscribed = new AtomicBoolean(false);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractToken(session);
        if (token == null || !jwtUtil.isValid(token)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid token"));
            return;
        }

        sessionManager.register(jwtUtil.getTenantId(token), jwtUtil.getUserId(token), session);
        ensureServerSubscribed();
        send(session, "{\"type\":\"connected\",\"serverId\":\"" + sessionManager.getServerId() + "\"}");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userKey = sessionManager.getUserKey(session.getId());
        if (userKey == null) return;

        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.path("type").asText("");

            switch (type) {
                case "ping": send(session, "{\"type\":\"pong\"}"); break;
                case "sync": handleSync(session, json, userKey); break;
                default: send(session, "{\"type\":\"error\",\"message\":\"Unknown type\"}");
            }
        } catch (Exception e) {
            log.error("WS message error from {}: {}", userKey, e.getMessage());
            send(session, "{\"type\":\"error\",\"message\":\"Invalid message format\"}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("WS transport error: {}", ex.getMessage());
    }

    // ═══ Sync ═══

    private void handleSync(WebSocketSession session, JsonNode json, String userKey) {
        JsonNode channels = json.get("channels");
        if (channels == null || !channels.isObject()) return;

        UUID tenantId;
        try {
            tenantId = UUID.fromString(userKey.split(":", 2)[0]);
        } catch (Exception e) { return; }

        channels.fields().forEachRemaining(entry -> {
            try {
                String lastMsgId = entry.getValue().asText();
                if (lastMsgId != null && !"null".equals(lastMsgId) && !lastMsgId.isBlank()) {
                    List<Message> missed = messageService.getMessagesAfter(
                            tenantId, UUID.fromString(entry.getKey()), UUID.fromString(lastMsgId), 100);
                    for (Message msg : missed) {
                        send(session, WsPayloadBuilder.buildMessageNew(msg, msg.getSenderName(), objectMapper));
                    }
                }
            } catch (Exception e) {
                log.error("Sync error channel={}: {}", entry.getKey(), e.getMessage());
            }
        });
        send(session, "{\"type\":\"sync_complete\"}");
    }

    // ═══ Pub/Sub ═══

    private void ensureServerSubscribed() {
        if (serverSubscribed.compareAndSet(false, true)) {
            pubSub.subscribe("ws:server:" + sessionManager.getServerId(), (channel, message) -> {
                try {
                    JsonNode json = objectMapper.readTree(message);
                    String targetTenantId = json.path("tenantId").asText("");
                    String targetChannelId = json.path("channelId").asText("");
                    if (targetChannelId.isEmpty()) return;

                    deliverToLocalMembers(targetTenantId, targetChannelId, message);
                } catch (Exception e) {
                    log.error("Pub/Sub error: {}", e.getMessage());
                }
            });
            log.info("Subscribed to Pub/Sub: ws:server:{}", sessionManager.getServerId());
        }
    }

    private void deliverToLocalMembers(String tenantId, String channelId, String message) {
        for (Map.Entry<String, WebSocketSession> entry : sessionManager.getAllSessions().entrySet()) {
            String userKey = entry.getKey();
            WebSocketSession session = entry.getValue();

            if (!session.isOpen() || !userKey.startsWith(tenantId + ":")) continue;

            String userId = userKey.substring(tenantId.length() + 1);
            try {
                if (channelService.isMember(UUID.fromString(channelId), UUID.fromString(userId))) {
                    send(session, message);
                }
            } catch (Exception e) {
                log.debug("Delivery check failed: user={} channel={}", userId, channelId);
            }
        }
    }

    // ═══ Helpers ═══

    private String extractToken(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        return Arrays.stream(uri.getQuery().split("&"))
                .filter(p -> p.startsWith("token="))
                .map(p -> p.substring(6))
                .findFirst().orElse(null);
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                synchronized (session) { session.sendMessage(new TextMessage(payload)); }
            }
        } catch (IOException e) {
            log.error("WS send failed: {}", e.getMessage());
            try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ex) { /* ignore */ }
        }
    }
}
