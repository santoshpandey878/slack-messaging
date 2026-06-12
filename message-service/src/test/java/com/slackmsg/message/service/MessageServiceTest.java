package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Mock private MessageStore messageStore;
    @Mock private ChannelServicePort channelService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private FanoutService fanoutService;
    @Mock private ObjectMapper objectMapper;

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
        Message savedMessage = buildMessage(messageId, "Hello world");

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "key-1")).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenReturn(savedMessage);

        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(messageId, response.getId());
        assertEquals("Hello world", response.getContent());
        verify(messageStore).save(any(Message.class));
        verify(idempotencyService).markCompleted(tenantId, userId, "key-1", messageId.toString());
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
        Message existingMessage = buildMessage(existingMsgId, "Hello");

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "dup-key"))
                .thenReturn(Optional.of(existingMsgId.toString()));
        when(messageStore.findById(tenantId, existingMsgId)).thenReturn(Optional.of(existingMessage));

        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(existingMsgId, response.getId());
        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_fanoutFailure() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");
        req.setIdempotencyKey("key-2");

        UUID messageId = UUID.randomUUID();
        Message savedMessage = buildMessage(messageId, "Hello");

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "key-2")).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenReturn(savedMessage);
        doThrow(new RuntimeException("Redis down")).when(fanoutService)
                .fanout(any(), any(), any(), any(), any());

        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(messageId, response.getId());
    }

    @Test
    void sendMessage_threadReply_success() {
        UUID parentId = UUID.randomUUID();
        Message parentMsg = buildMessage(parentId, "Parent");

        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Thread reply");
        req.setParentMessageId(parentId);
        req.setIdempotencyKey("thread-key");

        UUID replyId = UUID.randomUUID();
        Message savedReply = Message.builder()
                .id(replyId).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content("Thread reply").messageType(MessageType.TEXT)
                .parentMessageId(parentId).createdAt(Instant.now()).build();

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(parentMsg));
        when(idempotencyService.checkDuplicate(tenantId, userId, "thread-key")).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenReturn(savedReply);

        MessageResponse response = messageService.sendMessage(channelId, req);

        assertNotNull(response);
        assertEquals(parentId, response.getParentMessageId());
        verify(messageStore).incrementReplyCount(parentId);
    }

    @Test
    void sendMessage_replyToReply_blocked() {
        UUID parentId = UUID.randomUUID();
        Message replyMsg = Message.builder()
                .id(parentId).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content("Reply").messageType(MessageType.TEXT)
                .parentMessageId(UUID.randomUUID()).createdAt(Instant.now()).build();

        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Nested reply");
        req.setParentMessageId(parentId);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(replyMsg));

        assertThrows(IllegalArgumentException.class,
                () -> messageService.sendMessage(channelId, req));
        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_parentNotFound() {
        UUID parentId = UUID.randomUUID();
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Reply");
        req.setParentMessageId(parentId);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void getThreadReplies_success() {
        UUID parentId = UUID.randomUUID();
        Message reply = Message.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content("Reply").messageType(MessageType.TEXT)
                .parentMessageId(parentId).createdAt(Instant.now()).build();

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.getThreadReplies(eq(tenantId), eq(channelId), eq(parentId), anyInt()))
                .thenReturn(Collections.singletonList(reply));

        List<MessageResponse> replies = messageService.getThreadReplies(channelId, parentId, 50);

        assertEquals(1, replies.size());
        assertEquals(parentId, replies.get(0).getParentMessageId());
    }

    @Test
    void getHistory_success() {
        Instant cursor = Instant.now();
        Message msg = buildMessage(UUID.randomUUID(), "Hello");

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.getHistory(eq(tenantId), eq(channelId), eq(cursor), anyInt()))
                .thenReturn(Collections.singletonList(msg));

        List<MessageResponse> history = messageService.getHistory(channelId, cursor, 200);

        assertEquals(1, history.size());
        verify(messageStore).getHistory(tenantId, channelId, cursor, 100);
    }

    private Message buildMessage(UUID msgId, String content) {
        return Message.builder()
                .id(msgId).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content(content).messageType(MessageType.TEXT)
                .createdAt(Instant.now()).build();
    }
}
