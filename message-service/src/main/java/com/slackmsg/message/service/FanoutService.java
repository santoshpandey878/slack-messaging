package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.util.WsPayloadBuilder;
import com.slackmsg.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Handles real-time event fan-out to online/offline users.
 * Supports ANY event type — not just messages.
 *
 * Pattern for adding new event fan-out:
 * 1. Build payload with WsPayloadBuilder.buildXxx()
 * 2. Call fanoutEvent() with the payload
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

    private static final int UNREAD_TTL_DAYS = 30;

    /**
     * Fan-out a new message to all channel members.
     * Online: push via Redis Pub/Sub. Offline: increment unread count.
     */
    public void fanout(UUID tenantId, UUID channelId, Message message, UUID senderId, String senderName) {
        String payload = WsPayloadBuilder.buildMessageNew(message, senderName, objectMapper);
        fanoutToChannel(tenantId, channelId, payload, senderId, true);
    }

    /**
     * Fan-out ANY event to all channel members.
     * Use this for reactions, typing, pins, member changes, message edits, etc.
     *
     * @param tenantId      tenant scope
     * @param channelId     target channel
     * @param eventPayload  pre-built JSON payload from WsPayloadBuilder
     * @param excludeUserId user to exclude (e.g., sender), null to include all
     * @param trackUnread   whether to increment unread for offline users
     */
    public void fanoutEvent(UUID tenantId, UUID channelId, String eventPayload,
                            UUID excludeUserId, boolean trackUnread) {
        fanoutToChannel(tenantId, channelId, eventPayload, excludeUserId, trackUnread);
    }

    /**
     * Fan-out to ALL online users in a tenant (e.g., presence changes).
     * Does NOT track unread (presence is ephemeral).
     */
    public void fanoutToTenant(UUID tenantId, String eventPayload, UUID excludeUserId) {
        Set<String> publishedServers = new HashSet<>();
        Set<String> onlineKeys = cache.smembers(RedisKeys.onlineMembers(tenantId));

        if (onlineKeys == null || onlineKeys.isEmpty()) return;

        for (String memberIdStr : onlineKeys) {
            try {
                UUID memberId = UUID.fromString(memberIdStr);
                if (memberId.equals(excludeUserId)) continue;

                String connKey = RedisKeys.wsConnection(tenantId, memberId);
                String serverId = cache.hget(connKey, "serverId");

                if (serverId != null && publishedServers.add(serverId)) {
                    pubSub.publish("ws:server:" + serverId, eventPayload);
                }
            } catch (Exception e) {
                log.debug("Tenant fanout skip for {}: {}", memberIdStr, e.getMessage());
            }
        }
    }

    // ═══ Internal ═══

    private void fanoutToChannel(UUID tenantId, UUID channelId, String payload,
                                  UUID excludeUserId, boolean trackUnread) {
        List<UUID> memberIds = channelService.getMemberUserIds(channelId);
        Set<String> publishedServers = new HashSet<>();
        Set<String> unreadKeysToExpire = new HashSet<>();

        for (UUID memberId : memberIds) {
            if (memberId.equals(excludeUserId)) continue;

            String connKey = RedisKeys.wsConnection(tenantId, memberId);
            String serverId = cache.hget(connKey, "serverId");

            if (serverId != null) {
                if (publishedServers.add(serverId)) {
                    pubSub.publish("ws:server:" + serverId, payload);
                    log.debug("Published to server={} for channel={}", serverId, channelId);
                }
            } else if (trackUnread) {
                String unreadKey = RedisKeys.unread(tenantId, memberId);
                cache.hincrBy(unreadKey, channelId.toString(), 1);
                unreadKeysToExpire.add(unreadKey);
            }
        }

        for (String key : unreadKeysToExpire) {
            cache.expire(key, Duration.ofDays(UNREAD_TTL_DAYS));
        }
    }
}
