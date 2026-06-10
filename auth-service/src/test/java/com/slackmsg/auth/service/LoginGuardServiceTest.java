package com.slackmsg.auth.service;

import com.slackmsg.port.service.CacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginGuardServiceTest {

    @Mock
    private CacheService cache;

    @InjectMocks
    private LoginGuardService loginGuard;

    private static final String SLUG = "acme";
    private static final String EMAIL = "user@acme.com";
    private static final String KEY = "login:attempts:acme:user@acme.com";

    // ---- checkLocked ----

    @Test
    void checkLocked_noAttempts() {
        when(cache.get(KEY)).thenReturn(null);

        assertDoesNotThrow(() -> loginGuard.checkLocked(SLUG, EMAIL));
    }

    @Test
    void checkLocked_underLimit() {
        when(cache.get(KEY)).thenReturn("3");

        assertDoesNotThrow(() -> loginGuard.checkLocked(SLUG, EMAIL));
    }

    @Test
    void checkLocked_atLimit() {
        when(cache.get(KEY)).thenReturn("5");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loginGuard.checkLocked(SLUG, EMAIL));

        assertTrue(ex.getMessage().contains("Too many login attempts"));
    }

    @Test
    void checkLocked_corruptedValue() {
        when(cache.get(KEY)).thenReturn("invalid");

        assertDoesNotThrow(() -> loginGuard.checkLocked(SLUG, EMAIL));
        verify(cache).del(KEY);
    }

    // ---- recordFailedAttempt ----

    @Test
    void recordFailedAttempt_firstAttempt() {
        when(cache.increment(KEY)).thenReturn(1L);

        loginGuard.recordFailedAttempt(SLUG, EMAIL);

        verify(cache).increment(KEY);
        verify(cache).expire(eq(KEY), eq(Duration.ofMinutes(15)));
    }

    @Test
    void recordFailedAttempt_subsequentAttempt() {
        when(cache.increment(KEY)).thenReturn(3L);

        loginGuard.recordFailedAttempt(SLUG, EMAIL);

        verify(cache).increment(KEY);
        verify(cache, never()).expire(anyString(), any(Duration.class));
    }
}
