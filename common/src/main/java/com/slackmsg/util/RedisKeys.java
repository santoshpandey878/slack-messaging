package com.slackmsg.util;

import java.util.UUID;

/**
 * Centralized Redis key definitions. DRY — used by all services.
 */
public final class RedisKeys {

    private RedisKeys() {}

    public static String wsConnection(UUID tenantId, UUID userId) {
        return "ws:conn:" + tenantId + ":" + userId;
    }

    public static String wsServer(String serverId) {
        return "ws:server:" + serverId;
    }

    public static String unread(UUID tenantId, UUID userId) {
        return "unread:" + tenantId + ":" + userId;
    }

    public static String idempotency(UUID tenantId, UUID userId, String key) {
        return "idem:" + tenantId + ":" + userId + ":" + key;
    }

    public static String loginAttempts(String tenantSlug, String email) {
        return "login:attempts:" + tenantSlug + ":" + email;
    }

    public static String rateLimit(String prefix, String id, long window) {
        return "rl:" + prefix + ":" + id + ":" + window;
    }
}
