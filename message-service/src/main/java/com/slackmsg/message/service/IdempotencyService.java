package com.slackmsg.message.service;

import com.slackmsg.port.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages idempotency keys for deduplicating requests.
 * Separated from MessageService (Single Responsibility).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final CacheService cache;

    private static final String PREFIX = "idem:";
    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * Check if an operation with this key was already processed.
     * @return cached result ID if duplicate, empty if new.
     */
    public Optional<String> checkDuplicate(UUID tenantId, UUID userId, String key) {
        if (key == null || key.isBlank()) return Optional.empty();

        String cacheKey = PREFIX + tenantId + ":" + userId + ":" + key;
        String existing = cache.get(cacheKey);

        if (existing != null) {
            log.debug("Idempotency hit: key={} result={}", key, existing);
            return Optional.of(existing);
        }
        return Optional.empty();
    }

    /**
     * Mark an operation as completed with this key.
     */
    public void markCompleted(UUID tenantId, UUID userId, String key, String resultId) {
        if (key == null || key.isBlank()) return;

        String cacheKey = PREFIX + tenantId + ":" + userId + ":" + key;
        cache.set(cacheKey, resultId, TTL);
    }

    /**
     * Clear a stale key (e.g., when cached result no longer exists).
     */
    public void clearStale(UUID tenantId, UUID userId, String key) {
        if (key == null || key.isBlank()) return;

        String cacheKey = PREFIX + tenantId + ":" + userId + ":" + key;
        cache.del(cacheKey);
        log.warn("Cleared stale idempotency key: {}", key);
    }
}
