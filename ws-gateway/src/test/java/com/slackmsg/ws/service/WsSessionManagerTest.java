package com.slackmsg.ws.service;

import com.slackmsg.port.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WsSessionManagerTest {

    private CacheService cache;
    private WsSessionManager sessionManager;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cache = mock(CacheService.class);
        sessionManager = new WsSessionManager(cache);
    }

    @Test
    void register_addsSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-123");

        sessionManager.register(tenantId, userId, session);

        Map<String, WebSocketSession> all = sessionManager.getAllSessions();
        String userKey = tenantId + ":" + userId;
        assertTrue(all.containsKey(userKey));
        assertSame(session, all.get(userKey));
    }

    @Test
    void register_setsRedisConnection() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-456");

        sessionManager.register(tenantId, userId, session);

        String expectedConnKey = "ws:conn:" + tenantId + ":" + userId;
        verify(cache).hset(eq(expectedConnKey), eq("serverId"), anyString());
        verify(cache).hset(eq(expectedConnKey), eq("sessionId"), eq("session-456"));
        verify(cache).expire(eq(expectedConnKey), eq(Duration.ofHours(2)));
    }

    @Test
    void unregister_removesSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-789");

        sessionManager.register(tenantId, userId, session);
        assertEquals(1, sessionManager.getAllSessions().size());

        sessionManager.unregister(session);

        assertTrue(sessionManager.getAllSessions().isEmpty());
        assertEquals(0, sessionManager.getActiveCount());
    }

    @Test
    void getUserKey_returnsCorrectFormat() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-abc");

        sessionManager.register(tenantId, userId, session);

        String userKey = sessionManager.getUserKey("session-abc");
        assertEquals(tenantId + ":" + userId, userKey);
    }
}
