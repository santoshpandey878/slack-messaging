package com.slackmsg.message.service;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.dto.request.SendMessageRequest;
import com.slackmsg.dto.response.MessageResponse;
import com.slackmsg.port.repository.MessageStore;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageStore messageStore;

    @Mock
    private ChannelServicePort channelService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private FanoutService fanoutService;

    @InjectMocks
    private MessageService messageService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
        TenantContext.setDisplayName("Test User");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sendMessage_success() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello world");
        req.setIdempotencyKey("key-1");

        UUID messageId = UUID.randomUUID();
        Message savedMessage = Message.builder()
                .id(messageId)
                .tenantId(tenantId)
                .channelId(channelId)
                .senderId(userId)
                .senderName("Test User")
                .content("Hello world")
                .messageType(MessageType.TEXT)
                .createdAt(Instant.now())
                .idempotencyKey("key-1")
                .build();

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "key-1")).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenReturn(savedMessage);

        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(messageId, response.getId());
        assertEquals(channelId, response.getChannelId());
        assertEquals(userId, response.getSenderId());
        assertEquals("Hello world", response.getContent());

        verify(messageStore).save(any(Message.class));
        verify(idempotencyService).markCompleted(tenantId, userId, "key-1", messageId.toString());
        verify(fanoutService).fanout(eq(tenantId), eq(channelId), any(Message.class), eq(userId), eq("Test User"));
    }

    @Test
    void sendMessage_notMember() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");

        when(channelService.isMember(channelId, userId)).thenReturn(false);

        assertThrows(SecurityException.class,
                () -> messageService.sendMessage(channelId, req));

        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_noContent() {
        SendMessageRequest req = new SendMessageRequest();
        // No content, no media

        when(channelService.isMember(channelId, userId)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> messageService.sendMessage(channelId, req));

        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_idempotent() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");
        req.setIdempotencyKey("dup-key");

        UUID existingMsgId = UUID.randomUUID();
        Message existingMessage = Message.builder()
                .id(existingMsgId)
                .tenantId(tenantId)
                .channelId(channelId)
                .senderId(userId)
                .senderName("Test User")
                .content("Hello")
                .messageType(MessageType.TEXT)
                .createdAt(Instant.now())
                .build();

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "dup-key"))
                .thenReturn(Optional.of(existingMsgId.toString()));
        when(messageStore.findById(tenantId, existingMsgId)).thenReturn(Optional.of(existingMessage));

        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(existingMsgId, response.getId());
        verify(messageStore, never()).save(any());
        verify(fanoutService, never()).fanout(any(), any(), any(), any(), any());
    }

    @Test
    void sendMessage_fanoutFailure() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");
        req.setIdempotencyKey("key-2");

        UUID messageId = UUID.randomUUID();
        Message savedMessage = Message.builder()
                .id(messageId)
                .tenantId(tenantId)
                .channelId(channelId)
                .senderId(userId)
                .senderName("Test User")
                .content("Hello")
                .messageType(MessageType.TEXT)
                .createdAt(Instant.now())
                .build();

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "key-2")).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenReturn(savedMessage);
        doThrow(new RuntimeException("Redis down")).when(fanoutService)
                .fanout(any(), any(), any(), any(), any());

        // Should NOT throw despite fanout failure (best-effort)
        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(messageId, response.getId());
        verify(messageStore).save(any(Message.class));
    }

    @Test
    void getHistory_success() {
        Instant cursor = Instant.now();
        Message msg = Message.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .channelId(channelId)
                .senderId(userId)
                .senderName("Test User")
                .content("Hello")
                .messageType(MessageType.TEXT)
                .createdAt(Instant.now())
                .build();

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.getHistory(eq(tenantId), eq(channelId), eq(cursor), anyInt()))
                .thenReturn(Collections.singletonList(msg));

        List<MessageResponse> history = messageService.getHistory(channelId, cursor, 200);

        assertNotNull(history);
        assertEquals(1, history.size());

        // Verify limit was clamped to 100 (max)
        verify(messageStore).getHistory(tenantId, channelId, cursor, 100);
    }
}
