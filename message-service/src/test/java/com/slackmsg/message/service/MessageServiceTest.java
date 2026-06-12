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

    private Message msg(UUID id, UUID parentId) {
        return Message.builder().id(id).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content("Hello").messageType(MessageType.TEXT)
                .parentMessageId(parentId).createdAt(Instant.now()).build();
    }

    @Test
    void sendMessage_success() {
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Hello"); req.setIdempotencyKey("k1");
        UUID mid = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "k1")).thenReturn(Optional.empty());
        when(messageStore.save(any())).thenReturn(msg(mid, null));
        MessageResponse r = messageService.sendMessage(channelId, req);
        assertEquals(mid, r.getId());
        verify(fanoutService).fanout(eq(tenantId), eq(channelId), any(), eq(userId), eq("Test User"));
    }

    @Test
    void sendMessage_notMember() {
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Hi");
        when(channelService.isMember(channelId, userId)).thenReturn(false);
        assertThrows(SecurityException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void sendMessage_noContent() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, new SendMessageRequest()));
    }

    @Test
    void sendMessage_idempotent() {
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Hi"); req.setIdempotencyKey("dup");
        UUID eid = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "dup")).thenReturn(Optional.of(eid.toString()));
        when(messageStore.findById(tenantId, eid)).thenReturn(Optional.of(msg(eid, null)));
        assertEquals(eid, messageService.sendMessage(channelId, req).getId());
        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_fanoutFailure_stillSucceeds() {
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Hi"); req.setIdempotencyKey("k2");
        UUID mid = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "k2")).thenReturn(Optional.empty());
        when(messageStore.save(any())).thenReturn(msg(mid, null));
        doThrow(new RuntimeException("Redis down")).when(fanoutService).fanout(any(), any(), any(), any(), any());
        assertNotNull(messageService.sendMessage(channelId, req));
    }

    @Test
    void getHistory_clampsLimit() {
        Instant cursor = Instant.now();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.getHistory(eq(tenantId), eq(channelId), eq(cursor), anyInt()))
                .thenReturn(List.of(msg(UUID.randomUUID(), null)));
        messageService.getHistory(channelId, cursor, 200);
        verify(messageStore).getHistory(tenantId, channelId, cursor, 100);
    }

    @Test
    void sendThreadReply_success() {
        UUID parentId = UUID.randomUUID();
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Reply"); req.setParentMessageId(parentId);
        UUID replyId = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(any(), any(), any())).thenReturn(Optional.empty());
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(msg(parentId, null)));
        when(messageStore.save(any())).thenReturn(msg(replyId, parentId));
        MessageResponse r = messageService.sendMessage(channelId, req);
        assertEquals(parentId, r.getParentMessageId());
        verify(messageStore).incrementReplyCount(parentId);
        verify(fanoutService).fanoutEvent(eq(tenantId), eq(channelId), any(), eq(userId), eq(false));
    }

    @Test
    void sendThreadReply_parentNotFound() {
        UUID parentId = UUID.randomUUID();
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Reply"); req.setParentMessageId(parentId);
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void sendThreadReply_replyToReplyBlocked() {
        UUID gpId = UUID.randomUUID(), parentId = UUID.randomUUID();
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Nested"); req.setParentMessageId(parentId);
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(msg(parentId, gpId)));
        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test
    void getThreadReplies_success() {
        UUID parentId = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(msg(parentId, null)));
        when(messageStore.getThreadReplies(eq(tenantId), eq(channelId), eq(parentId), isNull(), anyInt()))
                .thenReturn(List.of(msg(UUID.randomUUID(), parentId)));
        List<MessageResponse> result = messageService.getThreadReplies(channelId, parentId, null, 50);
        assertEquals(2, result.size()); // parent + reply
    }
}
