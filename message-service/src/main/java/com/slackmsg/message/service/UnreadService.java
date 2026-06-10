package com.slackmsg.message.service;

import com.slackmsg.port.service.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Manages unread message counts per user per channel.
 * Separated from MessageService (Single Responsibility).
 */
@Service
@RequiredArgsConstructor
public class UnreadService {

    private final CacheService cache;

    private static final String PREFIX = "unread:";
    private static final Duration TTL = Duration.ofDays(30);

    public Map<String, String> getCounts(UUID tenantId, UUID userId) {
        return cache.hgetAll(key(tenantId, userId));
    }

    public void markRead(UUID tenantId, UUID userId, UUID channelId) {
        String k = key(tenantId, userId);
        cache.hset(k, channelId.toString(), "0");
        cache.expire(k, TTL);
    }

    private String key(UUID tenantId, UUID userId) {
        return PREFIX + tenantId + ":" + userId;
    }
}
