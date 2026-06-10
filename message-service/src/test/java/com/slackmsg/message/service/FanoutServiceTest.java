package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.util.RedisKeys;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FanoutServiceTest {

    @Mock
    private ChannelServicePort channelService;

    @Mock
    private CacheService cache;

    @Mock
    private PubSubService pubSub;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private FanoutService fanoutService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(senderId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Message createTestMessage() {
        return Message.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .channelId(channelId)
                .senderId(senderId)
                .senderName("Sender")
                .content("test message")
                .messageType(MessageType.TEXT)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void fanout_onlineMember() {
        UUID memberId = UUID.randomUUID();
        Message message = createTestMessage();

        when(channelService.getMemberUserIds(channelId))
                .thenReturn(Arrays.asList(senderId, memberId));

        String connKey = RedisKeys.wsConnection(tenantId, memberId);
        when(cache.hget(connKey, "serverId")).thenReturn("server1");

        fanoutService.fanout(tenantId, channelId, message, senderId, "Sender");

        verify(pubSub).publish(eq("ws:server:server1"), anyString());
        // No unread for online member
        String unreadKey = RedisKeys.unread(tenantId, memberId);
        verify(cache, never()).hincrBy(eq(unreadKey), eq(channelId.toString()), anyLong());
    }

    @Test
    void fanout_offlineMember() {
        UUID memberId = UUID.randomUUID();
        Message message = createTestMessage();

        when(channelService.getMemberUserIds(channelId))
                .thenReturn(Arrays.asList(senderId, memberId));

        String connKey = RedisKeys.wsConnection(tenantId, memberId);
        when(cache.hget(connKey, "serverId")).thenReturn(null);

        fanoutService.fanout(tenantId, channelId, message, senderId, "Sender");

        // No publish for offline member
        verify(pubSub, never()).publish(anyString(), anyString());

        // Unread incremented
        String unreadKey = RedisKeys.unread(tenantId, memberId);
        verify(cache).hincrBy(unreadKey, channelId.toString(), 1);
        verify(cache).expire(eq(unreadKey), any());
    }

    @Test
    void fanout_excludesSender() {
        Message message = createTestMessage();

        // Only member is the sender
        when(channelService.getMemberUserIds(channelId))
                .thenReturn(Collections.singletonList(senderId));

        fanoutService.fanout(tenantId, channelId, message, senderId, "Sender");

        // No publish and no unread for sender
        verify(pubSub, never()).publish(anyString(), anyString());
        verify(cache, never()).hincrBy(anyString(), anyString(), anyLong());
    }

    @Test
    void fanout_deduplicateServers() {
        UUID member1 = UUID.randomUUID();
        UUID member2 = UUID.randomUUID();
        Message message = createTestMessage();

        when(channelService.getMemberUserIds(channelId))
                .thenReturn(Arrays.asList(senderId, member1, member2));

        // Both members on the same server
        String connKey1 = RedisKeys.wsConnection(tenantId, member1);
        String connKey2 = RedisKeys.wsConnection(tenantId, member2);
        when(cache.hget(connKey1, "serverId")).thenReturn("server1");
        when(cache.hget(connKey2, "serverId")).thenReturn("server1");

        fanoutService.fanout(tenantId, channelId, message, senderId, "Sender");

        // Publish called only once for deduplicated server
        verify(pubSub, times(1)).publish(eq("ws:server:server1"), anyString());
    }
}
