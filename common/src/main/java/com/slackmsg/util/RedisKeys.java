package com.slackmsg.util;

import java.util.UUID;

/**
 * Centralized Redis key definitions. DRY — used by all services.
 * To add a new feature's Redis keys: add method here, use across services.
 */
public final class RedisKeys {

    private RedisKeys() {}

    // ═══ WebSocket ═══

    public static String wsConnection(UUID tenantId, UUID userId) {
        return "ws:conn:" + tenantId + ":" + userId;
    }

    public static String wsServer(String serverId) {
        return "ws:server:" + serverId;
    }

    // ═══ Messaging ═══

    public static String unread(UUID tenantId, UUID userId) {
        return "unread:" + tenantId + ":" + userId;
    }

    public static String idempotency(UUID tenantId, UUID userId, String key) {
        return "idem:" + tenantId + ":" + userId + ":" + key;
    }

    // ═══ Auth ═══

    public static String loginAttempts(String tenantSlug, String email) {
        return "login:attempts:" + tenantSlug + ":" + email;
    }

    public static String rateLimit(String prefix, String id, long window) {
        return "rl:" + prefix + ":" + id + ":" + window;
    }

    // ═══ Presence ═══

    public static String presence(UUID tenantId, UUID userId) {
        return "presence:" + tenantId + ":" + userId;
    }

    public static String onlineMembers(UUID tenantId) {
        return "online:" + tenantId;
    }

    // ═══ Typing ═══

    public static String typing(UUID channelId) {
        return "typing:" + channelId;
    }

    // ═══ Reactions (cache) ═══

    public static String reactionCounts(UUID messageId) {
        return "rxn:counts:" + messageId;
    }

    // ═══ Thread (cache) ═══

    public static String threadReplyCount(UUID messageId) {
        return "thread:count:" + messageId;
    }
}
