package com.slackmsg.service;

import com.slackmsg.domain.entity.Tenant;
import com.slackmsg.domain.entity.User;
import com.slackmsg.handler.dto.request.LoginRequest;
import com.slackmsg.handler.dto.request.RegisterRequest;
import com.slackmsg.handler.dto.response.AuthResponse;
import com.slackmsg.port.repository.TenantStore;
import com.slackmsg.port.repository.UserStore;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServiceTest {

    @Mock private TenantStore tenantStore;
    @Mock private UserStore userStore;
    @Mock private JwtUtil jwtUtil;
    @Mock private CacheService cache;

    private AuthService authService;
    private PasswordService passwordService;
    private LoginGuardService loginGuard;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setup() {
        passwordService = new PasswordService();
        loginGuard = new LoginGuardService(cache);
        authService = new AuthService(tenantStore, userStore, jwtUtil, passwordService, loginGuard);
    }

    @Test
    void register_success() {
        RegisterRequest req = new RegisterRequest();
        req.setTenantName("Porter"); req.setTenantSlug("porter");
        req.setEmail("admin@porter.com"); req.setDisplayName("Admin"); req.setPassword("test123");

        when(tenantStore.existsBySlug("porter")).thenReturn(false);
        when(tenantStore.save(any(Tenant.class))).thenAnswer(i -> { Tenant t = i.getArgument(0); t.setId(TENANT_ID); return t; });
        when(userStore.save(any(User.class))).thenAnswer(i -> { User u = i.getArgument(0); u.setId(USER_ID); return u; });
        when(jwtUtil.generateToken(any(), any(), any(), any())).thenReturn("jwt-token");

        AuthResponse resp = authService.register(req);
        assertNotNull(resp);
        assertEquals("jwt-token", resp.getToken());
        assertEquals("admin", resp.getRole());
    }

    @Test
    void register_duplicateSlug_throws() {
        RegisterRequest req = new RegisterRequest();
        req.setTenantSlug("porter"); req.setTenantName("P"); req.setEmail("a@b.com"); req.setDisplayName("A"); req.setPassword("x");
        when(tenantStore.existsBySlug("porter")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
    }

    @Test
    void login_success() {
        LoginRequest req = new LoginRequest();
        req.setTenantSlug("porter"); req.setEmail("admin@porter.com"); req.setPassword("test123");

        Tenant tenant = Tenant.builder().id(TENANT_ID).slug("porter").build();
        User user = User.builder().id(USER_ID).tenantId(TENANT_ID).email("admin@porter.com")
                .displayName("Admin").passwordHash(passwordService.encode("test123"))
                .role("admin").status("active").build();

        when(cache.get(anyString())).thenReturn(null);
        when(tenantStore.findBySlug("porter")).thenReturn(Optional.of(tenant));
        when(userStore.findByEmail(TENANT_ID, "admin@porter.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(any(), any(), any(), any())).thenReturn("jwt");

        AuthResponse resp = authService.login(req);
        assertEquals("jwt", resp.getToken());
        assertEquals("Admin", resp.getDisplayName());
    }

    @Test
    void login_wrongPassword_throws() {
        LoginRequest req = new LoginRequest();
        req.setTenantSlug("porter"); req.setEmail("admin@porter.com"); req.setPassword("wrong");

        Tenant tenant = Tenant.builder().id(TENANT_ID).slug("porter").build();
        User user = User.builder().id(USER_ID).tenantId(TENANT_ID)
                .passwordHash(passwordService.encode("correct")).status("active").build();

        when(cache.get(anyString())).thenReturn(null);
        when(tenantStore.findBySlug("porter")).thenReturn(Optional.of(tenant));
        when(userStore.findByEmail(TENANT_ID, "admin@porter.com")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class, () -> authService.login(req));
        verify(cache).increment(anyString()); // attempt tracked
    }

    @Test
    void login_tooManyAttempts_blocked() {
        LoginRequest req = new LoginRequest();
        req.setTenantSlug("porter"); req.setEmail("admin@porter.com"); req.setPassword("x");
        when(cache.get(anyString())).thenReturn("5");
        assertThrows(IllegalArgumentException.class, () -> authService.login(req));
        verify(tenantStore, never()).findBySlug(any());
    }

    @Test
    void login_deactivated_throws() {
        LoginRequest req = new LoginRequest();
        req.setTenantSlug("porter"); req.setEmail("admin@porter.com"); req.setPassword("test123");

        Tenant tenant = Tenant.builder().id(TENANT_ID).slug("porter").build();
        User user = User.builder().id(USER_ID).tenantId(TENANT_ID)
                .passwordHash(passwordService.encode("test123")).status("deactivated").build();

        when(cache.get(anyString())).thenReturn(null);
        when(tenantStore.findBySlug("porter")).thenReturn(Optional.of(tenant));
        when(userStore.findByEmail(TENANT_ID, "admin@porter.com")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class, () -> authService.login(req));
    }
}
