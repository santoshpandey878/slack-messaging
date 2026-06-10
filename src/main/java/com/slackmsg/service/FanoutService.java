package com.slackmsg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.util.WsPayloadBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Handles message fan-out to online/offline users.
 * Separated from MessageService (Single Responsibility).
 *
 * MVP: synchronous, best-effort.
 * Scale: swap to async Kafka consumer (same interface).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FanoutService {

    private final ChannelServicePort channelService;
    private final CacheService cache;
    private final PubSubService pubSub;
    private final ObjectMapper objectMapper;

    private static final String UNREAD_KEY_PREFIX = "unread:";
    private static final int UNREAD_TTL_DAYS = 30;

    /**
     * Fan-out a message to all channel members.
     * Online: push via Redis Pub/Sub.
     * Offline: increment unread count.
     */
    public void fanout(UUID tenantId, UUID channelId, Message message, UUID senderId, String senderName) {
        List<UUID> memberIds = channelService.getMemberUserIds(channelId);
        String payload = WsPayloadBuilder.buildMessageNew(message, senderName, objectMapper);
        Set<String> publishedServers = new HashSet<>();
        Set<String> unreadKeysToExpire = new HashSet<>();

        for (UUID memberId : memberIds) {
            if (memberId.equals(senderId)) continue;

            String connKey = WsSessionManager.connectionKey(tenantId, memberId);
            String serverId = cache.hget(connKey, "serverId");

            if (serverId != null) {
                if (publishedServers.add(serverId)) {
                    pubSub.publish("ws:server:" + serverId, payload);
                    log.debug("Published to server={} for channel={}", serverId, channelId);
                }
            } else {
                String unreadKey = UNREAD_KEY_PREFIX + tenantId + ":" + memberId;
                cache.hincrBy(unreadKey, channelId.toString(), 1);
                unreadKeysToExpire.add(unreadKey);
            }
        }

        for (String key : unreadKeysToExpire) {
            cache.expire(key, Duration.ofDays(UNREAD_TTL_DAYS));
        }
    }
}
