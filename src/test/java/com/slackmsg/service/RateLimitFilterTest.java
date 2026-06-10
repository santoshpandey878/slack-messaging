package com.slackmsg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.config.AppConfig;
import com.slackmsg.handler.middleware.RateLimitFilter;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock private CacheService cache;
    private RateLimitFilter filter;

    @BeforeEach
    void setup() {
        AppConfig appConfig = new AppConfig();
        AppConfig.RateLimit rl = new AppConfig.RateLimit();
        rl.setTenantPerSecond(100);
        rl.setUserPerSecond(10);
        appConfig.setRateLimit(rl);
        filter = new RateLimitFilter(cache, appConfig, new ObjectMapper());
    }

    @AfterEach
    void teardown() { TenantContext.clear(); }

    private MockHttpServletResponse runFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/channels");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void underLimit_passes() throws Exception {
        TenantContext.setTenantId(UUID.randomUUID());
        TenantContext.setUserId(UUID.randomUUID());
        when(cache.increment(anyString())).thenReturn(5L);
        assertNotEquals(429, runFilter().getStatus());
    }

    @Test
    void tenantLimitExceeded_returns429() throws Exception {
        TenantContext.setTenantId(UUID.randomUUID());
        TenantContext.setUserId(UUID.randomUUID());
        when(cache.increment(anyString())).thenReturn(101L);
        MockHttpServletResponse resp = runFilter();
        assertEquals(429, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("Tenant rate limit"));
    }

    @Test
    void userLimitExceeded_returns429() throws Exception {
        TenantContext.setTenantId(UUID.randomUUID());
        TenantContext.setUserId(UUID.randomUUID());
        when(cache.increment(anyString())).thenReturn(5L, 11L);
        MockHttpServletResponse resp = runFilter();
        assertEquals(429, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("User rate limit"));
    }

    @Test
    void noTenantContext_passesWithoutCheck() throws Exception {
        assertNotEquals(429, runFilter().getStatus());
        verify(cache, never()).increment(anyString());
    }
}
