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
import java.util.*;

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
    @InjectMocks private MessageService messageService;

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
    void tearDown() { TenantContext.clear(); }

    private Message buildMessage(UUID msgId, UUID parentId) {
        return Message.builder().id(msgId).tenantId(tenantId).channelId(channelId)
                .senderId(userId).senderName("Test User").content("Hello")
                .messageType(MessageType.TEXT).parentMessageId(parentId)
                .createdAt(Instant.now()).build();
    }

    @Test
    void sendMessage_success() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello world");
        req.setIdempotencyKey("key-1");

        UUID messageId = UUID.randomUUID();
        Message saved = buildMessage(messageId, null);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "key-1")).thenReturn(Optional.empty());
        when(messageStore.save(any())).thenReturn(saved);

        MessageResponse response = messageService.sendMessage(channelId, req);
        assertNotNull(response);
        assertEquals(messageId, response.getId());
        verify(messageStore).save(any());
        verify(fanoutService).fanout(eq(tenantId), eq(channelId), any(), eq(userId), eq("Test User"));
    }

    @Test
    void sendMessage_notMember() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");
        when(channelService.isMember(channelId, userId)).thenReturn(false);
        assertThrows(SecurityException.class, () -> messageService.sendMessage(channelId, req));
        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_noContent() {
        SendMessageRequest req = new SendMessageRequest();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void sendMessage_idempotent() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");
        req.setIdempotencyKey("dup-key");

        UUID existingId = UUID.randomUUID();
        Message existing = buildMessage(existingId, null);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "dup-key")).thenReturn(Optional.of(existingId.toString()));
        when(messageStore.findById(tenantId, existingId)).thenReturn(Optional.of(existing));

        MessageResponse response = messageService.sendMessage(channelId, req);
        assertEquals(existingId, response.getId());
        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_fanoutFailure() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello");
        req.setIdempotencyKey("key-2");

        UUID messageId = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "key-2")).thenReturn(Optional.empty());
        when(messageStore.save(any())).thenReturn(buildMessage(messageId, null));
        doThrow(new RuntimeException("Redis down")).when(fanoutService).fanout(any(), any(), any(), any(), any());

        MessageResponse response = messageService.sendMessage(channelId, req);
        assertNotNull(response);
    }

    @Test
    void getHistory_success() {
        Instant cursor = Instant.now();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.getHistory(eq(tenantId), eq(channelId), eq(cursor), anyInt()))
                .thenReturn(List.of(buildMessage(UUID.randomUUID(), null)));

        List<MessageResponse> history = messageService.getHistory(channelId, cursor, 200);
        assertEquals(1, history.size());
        verify(messageStore).getHistory(tenantId, channelId, cursor, 100); // clamped
    }

    // ═══ Thread Tests ═══

    @Test
    void sendThreadReply_success() {
        UUID parentId = UUID.randomUUID();
        Message parent = buildMessage(parentId, null); // top-level
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Thread reply");
        req.setParentMessageId(parentId);

        UUID replyId = UUID.randomUUID();
        Message reply = buildMessage(replyId, parentId);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(any(), any(), any())).thenReturn(Optional.empty());
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(parent));
        when(messageStore.save(any())).thenReturn(reply);

        MessageResponse response = messageService.sendMessage(channelId, req);
        assertNotNull(response);
        assertEquals(parentId, response.getParentMessageId());
        verify(messageStore).incrementReplyCount(parentId);
        // Thread replies use fanoutEvent, not fanout
        verify(fanoutService).fanoutEvent(eq(tenantId), eq(channelId), any(), eq(userId), eq(false));
    }

    @Test
    void sendThreadReply_parentNotFound() {
        UUID parentId = UUID.randomUUID();
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Reply");
        req.setParentMessageId(parentId);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void sendThreadReply_replyToReply_blocked() {
        UUID grandparentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Message parent = buildMessage(parentId, grandparentId); // this is itself a reply

        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Nested reply");
        req.setParentMessageId(parentId);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(parent));

        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void getThreadReplies_success() {
        UUID parentId = UUID.randomUUID();
        Message parent = buildMessage(parentId, null);
        Message reply = buildMessage(UUID.randomUUID(), parentId);

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(parent));
        when(messageStore.getThreadReplies(eq(tenantId), eq(channelId), eq(parentId), isNull(), anyInt()))
                .thenReturn(List.of(reply));

        List<MessageResponse> result = messageService.getThreadReplies(channelId, parentId, null, 50);
        assertEquals(2, result.size()); // parent + 1 reply
        assertEquals(parentId, result.get(0).getId()); // first is parent
    }
}
