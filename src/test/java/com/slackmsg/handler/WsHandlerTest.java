package com.slackmsg.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.MessageServicePort;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.service.WsSessionManager;
import com.slackmsg.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class WsHandlerTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private CacheService cache;
    @Mock private PubSubService pubSub;
    @Mock private MessageServicePort messageService;
    @Mock private ChannelServicePort channelService;

    private WsHandler wsHandler;
    private WsSessionManager sessionManager;
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final String VALID_TOKEN = "valid-jwt-token";

    @BeforeEach
    void setup() {
        sessionManager = new WsSessionManager(cache);
        wsHandler = new WsHandler(jwtUtil, pubSub, messageService, channelService, objectMapper, sessionManager);
    }

    // ═══ Connection ═══

    @Test
    void connect_valid_registersAndSendsWelcome() throws Exception {
        WebSocketSession session = mockSession(VALID_TOKEN);
        when(jwtUtil.isValid(VALID_TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(jwtUtil.getTenantId(VALID_TOKEN)).thenReturn(TENANT_ID);

        wsHandler.afterConnectionEstablished(session);

        verify(cache, atLeast(2)).hset(anyString(), anyString(), anyString());
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertTrue(captor.getValue().getPayload().contains("\"type\":\"connected\""));
        assertEquals(1, sessionManager.getActiveCount());
    }

    @Test
    void connect_invalidToken_closes() throws Exception {
        WebSocketSession session = mockSession("bad");
        when(jwtUtil.isValid("bad")).thenReturn(false);
        wsHandler.afterConnectionEstablished(session);
        verify(session).close(any(CloseStatus.class));
        assertEquals(0, sessionManager.getActiveCount());
    }

    @Test
    void connect_noToken_closes() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("ws://localhost/ws"));
        when(session.isOpen()).thenReturn(true);
        wsHandler.afterConnectionEstablished(session);
        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void disconnect_cleansUp() throws Exception {
        WebSocketSession session = connectUser();
        assertEquals(1, sessionManager.getActiveCount());
        wsHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, sessionManager.getActiveCount());
    }

    // ═══ Messages ═══

    @Test
    void ping_respondsPong() throws Exception {
        WebSocketSession session = connectUser();
        wsHandler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("pong")));
    }

    @Test
    void unknownType_returnsError() throws Exception {
        WebSocketSession session = connectUser();
        wsHandler.handleTextMessage(session, new TextMessage("{\"type\":\"xyz\"}"));
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("error")));
    }

    @Test
    void invalidJson_returnsError() throws Exception {
        WebSocketSession session = connectUser();
        wsHandler.handleTextMessage(session, new TextMessage("not-json"));
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("Invalid")));
    }

    // ═══ Sync ═══

    @Test
    void sync_deliversMissedMessages() throws Exception {
        WebSocketSession session = connectUser();
        UUID lastMsgId = UUID.randomUUID();
        Message missed = buildMessage("Missed!");

        when(messageService.getMessagesAfter(TENANT_ID, CHANNEL_ID, lastMsgId, 100)).thenReturn(List.of(missed));

        String syncReq = String.format("{\"type\":\"sync\",\"channels\":{\"%s\":\"%s\"}}", CHANNEL_ID, lastMsgId);
        wsHandler.handleTextMessage(session, new TextMessage(syncReq));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(3)).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("Missed!")));
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("sync_complete")));
    }

    // ═══ Pub/Sub delivery ═══

    @Test
    void pubSub_memberReceives() throws Exception {
        WebSocketSession session = connectUser();

        ArgumentCaptor<PubSubService.MessageHandler> handlerCaptor = ArgumentCaptor.forClass(PubSubService.MessageHandler.class);
        verify(pubSub).subscribe(anyString(), handlerCaptor.capture());

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "message.new", "channelId", CHANNEL_ID.toString(),
                "tenantId", TENANT_ID.toString(), "message", java.util.Map.of("content", "Hello")));
        handlerCaptor.getValue().onMessage("ch", payload);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("Hello")));
    }

    @Test
    void pubSub_nonMemberBlocked() throws Exception {
        WebSocketSession session = connectUser();

        ArgumentCaptor<PubSubService.MessageHandler> handlerCaptor = ArgumentCaptor.forClass(PubSubService.MessageHandler.class);
        verify(pubSub).subscribe(anyString(), handlerCaptor.capture());

        UUID otherChannel = UUID.randomUUID();
        when(channelService.isMember(otherChannel, USER_ID)).thenReturn(false);

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "message.new", "channelId", otherChannel.toString(),
                "tenantId", TENANT_ID.toString(), "message", java.util.Map.of("content", "Secret")));
        handlerCaptor.getValue().onMessage("ch", payload);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(1)).sendMessage(captor.capture());
        assertFalse(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("Secret")));
    }

    @Test
    void pubSub_differentTenantBlocked() throws Exception {
        WebSocketSession session = connectUser();

        ArgumentCaptor<PubSubService.MessageHandler> handlerCaptor = ArgumentCaptor.forClass(PubSubService.MessageHandler.class);
        verify(pubSub).subscribe(anyString(), handlerCaptor.capture());

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "message.new", "channelId", CHANNEL_ID.toString(),
                "tenantId", UUID.randomUUID().toString(), "message", java.util.Map.of("content", "Leak")));
        handlerCaptor.getValue().onMessage("ch", payload);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(1)).sendMessage(captor.capture());
        assertFalse(captor.getAllValues().stream().anyMatch(m -> m.getPayload().contains("Leak")));
    }

    // ═══ Helpers ═══

    private WebSocketSession connectUser() throws Exception {
        WebSocketSession session = mockSession(VALID_TOKEN);
        when(jwtUtil.isValid(VALID_TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(jwtUtil.getTenantId(VALID_TOKEN)).thenReturn(TENANT_ID);
        wsHandler.afterConnectionEstablished(session);
        return session;
    }

    private WebSocketSession mockSession(String token) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.getUri()).thenReturn(new URI("ws://localhost/ws?token=" + token));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private Message buildMessage(String content) {
        return Message.builder().id(UUID.randomUUID()).tenantId(TENANT_ID).channelId(CHANNEL_ID)
                .senderId(UUID.randomUUID()).content(content).messageType(MessageType.TEXT)
                .createdAt(Instant.now()).build();
    }
}
