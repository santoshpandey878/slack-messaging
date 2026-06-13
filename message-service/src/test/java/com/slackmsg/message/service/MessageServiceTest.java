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

    @InjectMocks private MessageService messageService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() { TenantContext.setTenantId(tenantId); TenantContext.setUserId(userId); TenantContext.setDisplayName("Test User"); }
    @AfterEach
    void tearDown() { TenantContext.clear(); }

    private Message msg(UUID id, String content) {
        return Message.builder().id(id).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content(content).messageType(MessageType.TEXT).createdAt(Instant.now()).build();
    }

    @Test void sendMessage_success() {
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Hello"); req.setIdempotencyKey("k1");
        UUID msgId = UUID.randomUUID();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(idempotencyService.checkDuplicate(tenantId, userId, "k1")).thenReturn(Optional.empty());
        when(messageStore.save(any())).thenReturn(msg(msgId, "Hello"));
        MessageResponse r = messageService.sendMessage(channelId, req);
        assertEquals(msgId, r.getId());
        verify(messageStore).save(any());
    }

    @Test void sendMessage_notMember() {
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Hi");
        when(channelService.isMember(channelId, userId)).thenReturn(false);
        assertThrows(SecurityException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test void sendMessage_noContent() {
        SendMessageRequest req = new SendMessageRequest();
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test void sendMessage_threadReply() {
        UUID parentId = UUID.randomUUID();
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Reply"); req.setParentMessageId(parentId); req.setIdempotencyKey("t1");
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(msg(parentId, "Parent")));
        when(idempotencyService.checkDuplicate(any(), any(), any())).thenReturn(Optional.empty());
        Message reply = msg(UUID.randomUUID(), "Reply"); reply.setParentMessageId(parentId);
        when(messageStore.save(any())).thenReturn(reply);
        MessageResponse r = messageService.sendMessage(channelId, req);
        assertEquals(parentId, r.getParentMessageId());
        verify(messageStore).incrementReplyCount(parentId);
    }

    @Test void sendMessage_replyToReply_blocked() {
        UUID parentId = UUID.randomUUID();
        Message replyMsg = msg(parentId, "R"); replyMsg.setParentMessageId(UUID.randomUUID());
        SendMessageRequest req = new SendMessageRequest(); req.setContent("Nested"); req.setParentMessageId(parentId);
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, parentId)).thenReturn(Optional.of(replyMsg));
        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(channelId, req));
    }

    @Test void getHistory_clampsLimit() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.getHistory(eq(tenantId), eq(channelId), any(), anyInt())).thenReturn(Collections.emptyList());
        messageService.getHistory(channelId, Instant.now(), 500);
        verify(messageStore).getHistory(eq(tenantId), eq(channelId), any(), eq(100));
    }

    @Test void searchMessages_emptyQuery() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> messageService.searchMessages(channelId, "", 20));
    }

    @Test void searchMessages_success() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.searchMessages(eq(tenantId), any(), eq("hello"), anyInt()))
                .thenReturn(Collections.singletonList(msg(UUID.randomUUID(), "hello world")));
        List<MessageResponse> results = messageService.searchMessages(channelId, "hello", 20);
        assertEquals(1, results.size());
    }
}
