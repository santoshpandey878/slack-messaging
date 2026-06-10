package com.slackmsg.handler.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.config.AppConfig;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.util.ApiResponse;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Per-tenant and per-user rate limiting using Redis sliding window.
 * Runs AFTER JwtAuthFilter (so TenantContext is already set).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final CacheService cache;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Only rate-limit if authenticated (TenantContext set)
        if (tenantId == null || userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Current second window
        long window = System.currentTimeMillis() / 1000;

        // Per-tenant rate limit
        String tenantKey = "rl:tenant:" + tenantId + ":" + window;
        Long tenantCount = cache.increment(tenantKey);
        if (tenantCount == 1) cache.expire(tenantKey, Duration.ofSeconds(2));

        if (tenantCount > appConfig.getRateLimit().getTenantPerSecond()) {
            log.warn("Tenant rate limited: {} count={}", tenantId, tenantCount);
            sendRateLimited(response, "Tenant rate limit exceeded");
            return;
        }

        // Per-user rate limit
        String userKey = "rl:user:" + userId + ":" + window;
        Long userCount = cache.increment(userKey);
        if (userCount == 1) cache.expire(userKey, Duration.ofSeconds(2));

        if (userCount > appConfig.getRateLimit().getUserPerSecond()) {
            log.warn("User rate limited: {} count={}", userId, userCount);
            sendRateLimited(response, "User rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendRateLimited(HttpServletResponse response, String message) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }
}
