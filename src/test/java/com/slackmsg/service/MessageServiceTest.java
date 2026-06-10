package com.slackmsg.service;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.handler.dto.request.SendMessageRequest;
import com.slackmsg.handler.dto.response.MessageResponse;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MessageServiceTest {

    @Mock private MessageStore messageStore;
    @Mock private ChannelServicePort channelService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private FanoutService fanoutService;

    @InjectMocks private MessageService messageService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID MSG_ID = UUID.randomUUID();

    @BeforeEach
    void setup() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("member");
        TenantContext.setDisplayName("Santosh");
    }

    @AfterEach
    void teardown() { TenantContext.clear(); }

    @Test
    void sendMessage_success() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello!");

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);
        when(idempotencyService.checkDuplicate(any(), any(), any())).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0); m.setId(MSG_ID); return m;
        });

        MessageResponse response = messageService.sendMessage(CHANNEL_ID, req);

        assertNotNull(response);
        assertEquals(MSG_ID, response.getId());
        assertEquals("Hello!", response.getContent());
        assertEquals(MessageType.TEXT, response.getMessageType());
        assertEquals("Santosh", response.getSenderName());
        verify(fanoutService).fanout(eq(TENANT_ID), eq(CHANNEL_ID), any(), eq(USER_ID), eq("Santosh"));
    }

    @Test
    void sendMessage_notMember_throws() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello!");

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(false);

        assertThrows(SecurityException.class, () -> messageService.sendMessage(CHANNEL_ID, req));
        verify(messageStore, never()).save(any());
    }

    @Test
    void sendMessage_idempotency_returnsCached() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello!");
        req.setIdempotencyKey("key-001");

        Message cached = Message.builder()
                .id(MSG_ID).tenantId(TENANT_ID).channelId(CHANNEL_ID).senderId(USER_ID)
                .senderName("Santosh").content("Hello!").messageType(MessageType.TEXT).createdAt(Instant.now()).build();

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);
        when(idempotencyService.checkDuplicate(TENANT_ID, USER_ID, "key-001")).thenReturn(Optional.of(MSG_ID.toString()));
        when(messageStore.findById(TENANT_ID, MSG_ID)).thenReturn(Optional.of(cached));

        MessageResponse response = messageService.sendMessage(CHANNEL_ID, req);

        assertEquals(MSG_ID, response.getId());
        verify(messageStore, never()).save(any());
        verify(fanoutService, never()).fanout(any(), any(), any(), any(), any());
    }

    @Test
    void sendMessage_withMedia_setsMediaType() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Check this");
        req.setMediaUrl("https://s3/img.png");
        req.setMediaType("image/png");

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);
        when(idempotencyService.checkDuplicate(any(), any(), any())).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0); m.setId(MSG_ID); return m;
        });

        MessageResponse response = messageService.sendMessage(CHANNEL_ID, req);

        assertEquals(MessageType.MEDIA, response.getMessageType());
    }

    @Test
    void sendMessage_emptyContentAndMedia_throws() {
        SendMessageRequest req = new SendMessageRequest();

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> messageService.sendMessage(CHANNEL_ID, req));
    }

    @Test
    void sendMessage_fanoutFailure_doesNotFailSend() {
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("Hello!");

        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);
        when(idempotencyService.checkDuplicate(any(), any(), any())).thenReturn(Optional.empty());
        when(messageStore.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0); m.setId(MSG_ID); return m;
        });
        doThrow(new RuntimeException("Redis down")).when(fanoutService).fanout(any(), any(), any(), any(), any());

        // Should NOT throw — fan-out is best-effort
        MessageResponse response = messageService.sendMessage(CHANNEL_ID, req);
        assertNotNull(response);
    }

    @Test
    void getHistory_notMember_throws() {
        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(false);
        assertThrows(SecurityException.class, () -> messageService.getHistory(CHANNEL_ID, null, 50));
    }

    @Test
    void getHistory_clipsLimit() {
        when(channelService.isMember(CHANNEL_ID, USER_ID)).thenReturn(true);
        when(messageStore.getHistory(TENANT_ID, CHANNEL_ID, null, 100)).thenReturn(List.of());

        messageService.getHistory(CHANNEL_ID, null, 999);

        verify(messageStore).getHistory(TENANT_ID, CHANNEL_ID, null, 100);
    }
}
