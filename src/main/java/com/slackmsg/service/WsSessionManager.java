package com.slackmsg.service;

import com.slackmsg.port.service.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket session state: registration, lookup, cleanup.
 * Extracted from WsHandler (SRP).
 */
@Service
@Slf4j
public class WsSessionManager {

    private final CacheService cache;

    // userKey (tenantId:userId) → session
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToUserKey = new ConcurrentHashMap<>();

    private final String serverId = "ws-server-" + UUID.randomUUID().toString().substring(0, 8);

    public WsSessionManager(CacheService cache) {
        this.cache = cache;
    }

    public void register(UUID tenantId, UUID userId, WebSocketSession session) {
        String userKey = tenantId + ":" + userId;
        sessions.put(userKey, session);
        sessionToUserKey.put(session.getId(), userKey);

        String connKey = connectionKey(tenantId, userId);
        cache.hset(connKey, "serverId", serverId);
        cache.hset(connKey, "sessionId", session.getId());
        cache.expire(connKey, Duration.ofHours(2));

        log.info("WS registered: userId={} tenantId={} serverId={}", userId, tenantId, serverId);
    }

    public void unregister(WebSocketSession session) {
        String userKey = sessionToUserKey.remove(session.getId());
        if (userKey == null) return;

        sessions.remove(userKey);
        cache.del("ws:conn:" + userKey); // userKey = tenantId:userId
        log.info("WS unregistered: userKey={}", userKey);
    }

    public String getUserKey(String sessionId) {
        return sessionToUserKey.get(sessionId);
    }

    public Map<String, WebSocketSession> getAllSessions() {
        return sessions;
    }

    public String getServerId() {
        return serverId;
    }

    public int getActiveCount() {
        return sessions.size();
    }

    /** Shared key format — used by FanoutService to look up connections. DRY. */
    public static String connectionKey(UUID tenantId, UUID userId) {
        return "ws:conn:" + tenantId + ":" + userId;
    }
}
