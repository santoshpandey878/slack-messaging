package com.slackmsg.port.service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction for caching. MVP: Redis. Scale: Redis Cluster (same interface).
 */
public interface CacheService {

    // String operations
    void set(String key, String value, Duration ttl);
    String get(String key);
    boolean exists(String key);
    Long increment(String key);

    // Hash operations (for unread counts, connection registry)
    void hset(String key, String field, String value);
    String hget(String key, String field);
    Map<String, String> hgetAll(String key);
    Long hincrBy(String key, String field, long delta);
    void hdel(String key, String field);

    // Set operations (for online members per channel)
    void sadd(String key, String... members);
    void srem(String key, String... members);
    Set<String> smembers(String key);
    Long scard(String key);

    // Key operations
    void expire(String key, Duration ttl);
    void del(String key);
}
