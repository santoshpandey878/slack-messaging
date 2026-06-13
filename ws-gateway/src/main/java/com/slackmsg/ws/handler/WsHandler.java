package com.slackmsg.ws.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.MessageServicePort;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.ws.service.WsSessionManager;
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

        UUID tenantId = jwtUtil.getTenantId(token);
        UUID userId = jwtUtil.getUserId(token);
        String displayName = jwtUtil.getDisplayName(token);

        sessionManager.register(tenantId, userId, session);
        ensureServerSubscribed();
        send(session, "{\"type\":\"connected\",\"serverId\":\"" + sessionManager.getServerId() + "\"}");

        // Presence: broadcast online status
        try {
            String payload = WsPayloadBuilder.buildPresenceChange(tenantId, userId, "online", objectMapper);
            deliverToTenantMembers(tenantId.toString(), payload);
        } catch (Exception e) {
            log.debug("Presence online fanout failed: {}", e.getMessage());
        }
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
                case "typing": handleTyping(json, userKey); break;
                default: send(session, "{\"type\":\"error\",\"message\":\"Unknown type\"}");
            }
        } catch (Exception e) {
            log.error("WS message error from {}: {}", userKey, e.getMessage());
            send(session, "{\"type\":\"error\",\"message\":\"Invalid message format\"}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userKey = sessionManager.getUserKey(session.getId());
        sessionManager.unregister(session);

        // Presence: broadcast offline status
        if (userKey != null) {
            try {
                String[] parts = userKey.split(":", 2);
                UUID tenantId = UUID.fromString(parts[0]);
                UUID userId = UUID.fromString(parts[1]);
                String payload = WsPayloadBuilder.buildPresenceChange(tenantId, userId, "offline", objectMapper);
                deliverToTenantMembers(tenantId.toString(), payload);
            } catch (Exception e) {
                log.debug("Presence offline fanout failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("WS transport error: {}", ex.getMessage());
    }

    // ═══ Typing ═══

    private void handleTyping(JsonNode json, String userKey) {
        String chId = json.path("channelId").asText("");
        if (chId.isEmpty()) return;

        try {
            String[] parts = userKey.split(":", 2);
            UUID tenantId = UUID.fromString(parts[0]);
            UUID userId = UUID.fromString(parts[1]);

            if (!channelService.isMember(UUID.fromString(chId), userId)) return;

            String displayName = json.path("displayName").asText("Someone");
            String payload = WsPayloadBuilder.buildTypingStart(tenantId, UUID.fromString(chId), userId, displayName, objectMapper);

            // Fan out to channel members directly (skip Redis pub/sub for ephemeral events)
            for (Map.Entry<String, WebSocketSession> entry : sessionManager.getAllSessions().entrySet()) {
                String key = entry.getKey();
                WebSocketSession s = entry.getValue();
                if (!s.isOpen() || !key.startsWith(parts[0] + ":") || key.equals(userKey)) continue;
                String uid = key.substring(parts[0].length() + 1);
                try {
                    if (channelService.isMember(UUID.fromString(chId), UUID.fromString(uid))) {
                        send(s, payload);
                    }
                } catch (Exception e) { /* skip */ }
            }
        } catch (Exception e) {
            log.debug("Typing fanout failed: {}", e.getMessage());
        }
    }

    // ═══ Sync ═══

    private void handleSync(WebSocketSession session, JsonNode json, String userKey) {
        JsonNode channels = json.get("channels");
        if (channels == null || !channels.isObject()) return;

        UUID tenantId;
        try { tenantId = UUID.fromString(userKey.split(":", 2)[0]); }
        catch (Exception e) { return; }

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
                    if (targetTenantId.isEmpty()) return;

                    if (targetChannelId.isEmpty() || "null".equals(targetChannelId)) {
                        deliverToTenantMembers(targetTenantId, message);
                    } else {
                        deliverToChannelMembers(targetTenantId, targetChannelId, message);
                    }
                } catch (Exception e) {
                    log.error("Pub/Sub error: {}", e.getMessage());
                }
            });
            log.info("Subscribed to Pub/Sub: ws:server:{}", sessionManager.getServerId());
        }
    }

    private void deliverToChannelMembers(String tenantId, String channelId, String message) {
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

    private void deliverToTenantMembers(String tenantId, String message) {
        for (Map.Entry<String, WebSocketSession> entry : sessionManager.getAllSessions().entrySet()) {
            if (!entry.getValue().isOpen() || !entry.getKey().startsWith(tenantId + ":")) continue;
            send(entry.getValue(), message);
        }
    }

    // ═══ Helpers ═══

    private String extractToken(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        return Arrays.stream(uri.getQuery().split("&"))
                .filter(p -> p.startsWith("token=")).map(p -> p.substring(6)).findFirst().orElse(null);
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) { synchronized (session) { session.sendMessage(new TextMessage(payload)); } }
        } catch (IOException e) {
            log.error("WS send failed: {}", e.getMessage());
            try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ex) { /* ignore */ }
        }
    }
}
