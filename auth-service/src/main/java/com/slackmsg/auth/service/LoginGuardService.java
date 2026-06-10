package com.slackmsg.auth.service;

import com.slackmsg.port.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginGuardService {

    private final CacheService cache;

    private static final String PREFIX = "login:attempts:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    public void checkLocked(String tenantSlug, String email) {
        String key = key(tenantSlug, email);
        String attempts = cache.get(key);
        if (attempts == null) return;

        try {
            if (Integer.parseInt(attempts) >= MAX_ATTEMPTS) {
                log.warn("Login blocked: email={} slug={}", email, tenantSlug);
                throw new IllegalArgumentException("Too many login attempts. Try again in 15 minutes.");
            }
        } catch (NumberFormatException e) {
            log.warn("Corrupted login counter, resetting: {}", key);
            cache.del(key);
        }
    }

    public void recordFailedAttempt(String tenantSlug, String email) {
        String key = key(tenantSlug, email);
        Long count = cache.increment(key);
        if (count != null && count == 1) cache.expire(key, LOCKOUT_DURATION);
    }

    public void clearAttempts(String tenantSlug, String email) {
        cache.del(key(tenantSlug, email));
    }

    private String key(String slug, String email) {
        return PREFIX + slug + ":" + email;
    }
}
