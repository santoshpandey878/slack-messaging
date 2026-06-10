package com.slackmsg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.PubSubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FanoutTest {

    @Mock private ChannelServicePort channelService;
    @Mock private CacheService cache;
    @Mock private PubSubService pubSub;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private FanoutService fanoutService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();

    private Message buildMessage() {
        return Message.builder().id(UUID.randomUUID()).tenantId(TENANT_ID).channelId(CHANNEL_ID)
                .senderId(SENDER_ID).senderName("Santosh").content("Hello")
                .messageType(MessageType.TEXT).createdAt(Instant.now()).build();
    }

    @Test
    void senderIsSkipped() {
        when(channelService.getMemberUserIds(CHANNEL_ID)).thenReturn(List.of(SENDER_ID));
        fanoutService.fanout(TENANT_ID, CHANNEL_ID, buildMessage(), SENDER_ID, "Santosh");
        verify(pubSub, never()).publish(anyString(), anyString());
        verify(cache, never()).hincrBy(anyString(), anyString(), anyLong());
    }

    @Test
    void onlineUserGetsPush() {
        UUID onlineUser = UUID.randomUUID();
        when(channelService.getMemberUserIds(CHANNEL_ID)).thenReturn(List.of(SENDER_ID, onlineUser));
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + onlineUser, "serverId")).thenReturn("ws-01");

        fanoutService.fanout(TENANT_ID, CHANNEL_ID, buildMessage(), SENDER_ID, "Santosh");

        verify(pubSub).publish(eq("ws:server:ws-01"), anyString());
    }

    @Test
    void offlineUserGetsUnread() {
        UUID offlineUser = UUID.randomUUID();
        when(channelService.getMemberUserIds(CHANNEL_ID)).thenReturn(List.of(SENDER_ID, offlineUser));
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + offlineUser, "serverId")).thenReturn(null);

        fanoutService.fanout(TENANT_ID, CHANNEL_ID, buildMessage(), SENDER_ID, "Santosh");

        verify(pubSub, never()).publish(anyString(), anyString());
        verify(cache).hincrBy(eq("unread:" + TENANT_ID + ":" + offlineUser), eq(CHANNEL_ID.toString()), eq(1L));
    }

    @Test
    void mixedOnlineOffline() {
        UUID online1 = UUID.randomUUID(), online2 = UUID.randomUUID();
        UUID offline1 = UUID.randomUUID();

        when(channelService.getMemberUserIds(CHANNEL_ID)).thenReturn(List.of(SENDER_ID, online1, online2, offline1));
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + online1, "serverId")).thenReturn("ws-01");
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + online2, "serverId")).thenReturn("ws-02");
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + offline1, "serverId")).thenReturn(null);

        fanoutService.fanout(TENANT_ID, CHANNEL_ID, buildMessage(), SENDER_ID, "Santosh");

        verify(pubSub).publish(eq("ws:server:ws-01"), anyString());
        verify(pubSub).publish(eq("ws:server:ws-02"), anyString());
        verify(cache).hincrBy(contains("unread"), eq(CHANNEL_ID.toString()), eq(1L));
    }

    @Test
    void sameServerDeduplication() {
        UUID user1 = UUID.randomUUID(), user2 = UUID.randomUUID();
        when(channelService.getMemberUserIds(CHANNEL_ID)).thenReturn(List.of(SENDER_ID, user1, user2));
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + user1, "serverId")).thenReturn("ws-01");
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + user2, "serverId")).thenReturn("ws-01");

        fanoutService.fanout(TENANT_ID, CHANNEL_ID, buildMessage(), SENDER_ID, "Santosh");

        verify(pubSub, times(1)).publish(eq("ws:server:ws-01"), anyString());
    }

    @Test
    void payloadContainsCorrectFields() {
        UUID onlineUser = UUID.randomUUID();
        when(channelService.getMemberUserIds(CHANNEL_ID)).thenReturn(List.of(SENDER_ID, onlineUser));
        when(cache.hget("ws:conn:" + TENANT_ID + ":" + onlineUser, "serverId")).thenReturn("ws-01");

        fanoutService.fanout(TENANT_ID, CHANNEL_ID, buildMessage(), SENDER_ID, "Santosh");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(pubSub).publish(anyString(), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"type\":\"message.new\""));
        assertTrue(payload.contains("\"senderName\":\"Santosh\""));
        assertTrue(payload.contains("\"content\":\"Hello\""));
    }
}
