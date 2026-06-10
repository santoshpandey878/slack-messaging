package com.slackmsg.auth.service;

import com.slackmsg.domain.entity.Tenant;
import com.slackmsg.domain.entity.User;
import com.slackmsg.dto.request.LoginRequest;
import com.slackmsg.dto.request.RegisterRequest;
import com.slackmsg.dto.response.AuthResponse;
import com.slackmsg.port.repository.TenantStore;
import com.slackmsg.port.repository.UserStore;
import com.slackmsg.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private TenantStore tenantStore;

    @Mock
    private UserStore userStore;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordService passwordService;

    @Mock
    private LoginGuardService loginGuard;

    @InjectMocks
    private AuthService authService;

    // ---- helpers ----

    private RegisterRequest registerRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setTenantName("Acme Corp");
        req.setTenantSlug("acme");
        req.setEmail("admin@acme.com");
        req.setDisplayName("Admin User");
        req.setPassword("secret123");
        return req;
    }

    private LoginRequest loginRequest() {
        LoginRequest req = new LoginRequest();
        req.setTenantSlug("acme");
        req.setEmail("admin@acme.com");
        req.setPassword("secret123");
        return req;
    }

    private Tenant tenant() {
        return Tenant.builder()
                .id(UUID.randomUUID())
                .name("Acme Corp")
                .slug("acme")
                .build();
    }

    private User user(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email("admin@acme.com")
                .displayName("Admin User")
                .passwordHash("hashed")
                .role("admin")
                .status("active")
                .build();
    }

    // ---- register ----

    @Test
    void register_success() {
        RegisterRequest req = registerRequest();
        Tenant savedTenant = tenant();
        User savedUser = user(savedTenant.getId());

        when(tenantStore.existsBySlug("acme")).thenReturn(false);
        when(tenantStore.save(any(Tenant.class))).thenReturn(savedTenant);
        when(passwordService.encode("secret123")).thenReturn("hashed");
        when(userStore.save(any(User.class))).thenReturn(savedUser);
        when(jwtUtil.generateToken(
                eq(savedUser.getId()), eq(savedTenant.getId()),
                eq(savedUser.getRole()), eq(savedUser.getDisplayName())
        )).thenReturn("jwt-token");

        AuthResponse response = authService.register(req);

        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
        assertEquals(savedUser.getId(), response.getUserId());
        assertEquals(savedTenant.getId(), response.getTenantId());
        assertEquals("Admin User", response.getDisplayName());
        assertEquals("admin", response.getRole());

        verify(tenantStore).existsBySlug("acme");
        verify(tenantStore).save(any(Tenant.class));
        verify(userStore).save(any(User.class));
        verify(jwtUtil).generateToken(
                eq(savedUser.getId()), eq(savedTenant.getId()),
                eq(savedUser.getRole()), eq(savedUser.getDisplayName()));
    }

    @Test
    void register_duplicateSlug() {
        RegisterRequest req = registerRequest();
        when(tenantStore.existsBySlug("acme")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.register(req));

        assertTrue(ex.getMessage().contains("Tenant slug already exists"));
        verify(tenantStore, never()).save(any());
        verify(userStore, never()).save(any());
    }

    // ---- login ----

    @Test
    void login_success() {
        LoginRequest req = loginRequest();
        Tenant t = tenant();
        User u = user(t.getId());

        when(tenantStore.findBySlug("acme")).thenReturn(Optional.of(t));
        when(userStore.findByEmail(t.getId(), "admin@acme.com")).thenReturn(Optional.of(u));
        when(passwordService.matches("secret123", "hashed")).thenReturn(true);
        when(jwtUtil.generateToken(
                eq(u.getId()), eq(t.getId()), eq(u.getRole()), eq(u.getDisplayName())
        )).thenReturn("jwt-token");

        AuthResponse response = authService.login(req);

        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
        assertEquals(u.getId(), response.getUserId());

        verify(loginGuard).checkLocked("acme", "admin@acme.com");
        verify(loginGuard).clearAttempts("acme", "admin@acme.com");
        verify(loginGuard, never()).recordFailedAttempt(anyString(), anyString());
    }

    @Test
    void login_wrongPassword() {
        LoginRequest req = loginRequest();
        Tenant t = tenant();
        User u = user(t.getId());

        when(tenantStore.findBySlug("acme")).thenReturn(Optional.of(t));
        when(userStore.findByEmail(t.getId(), "admin@acme.com")).thenReturn(Optional.of(u));
        when(passwordService.matches("secret123", "hashed")).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.login(req));

        assertEquals("Invalid credentials", ex.getMessage());
        verify(loginGuard).recordFailedAttempt("acme", "admin@acme.com");
        verify(loginGuard, never()).clearAttempts(anyString(), anyString());
    }

    @Test
    void login_tenantNotFound() {
        LoginRequest req = loginRequest();

        when(tenantStore.findBySlug("acme")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.login(req));

        assertEquals("Invalid credentials", ex.getMessage());
        verify(loginGuard).recordFailedAttempt("acme", "admin@acme.com");
    }

    @Test
    void login_deactivatedUser() {
        LoginRequest req = loginRequest();
        Tenant t = tenant();
        User u = user(t.getId());
        u.setStatus("inactive");

        when(tenantStore.findBySlug("acme")).thenReturn(Optional.of(t));
        when(userStore.findByEmail(t.getId(), "admin@acme.com")).thenReturn(Optional.of(u));
        when(passwordService.matches("secret123", "hashed")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.login(req));

        assertEquals("User account is deactivated", ex.getMessage());
    }

    // ---- addUser ----

    @Test
    void addUser_success() {
        UUID tenantId = UUID.randomUUID();
        User savedUser = user(tenantId);
        savedUser.setRole("member");

        when(userStore.existsByEmail(tenantId, "new@acme.com")).thenReturn(false);
        when(passwordService.encode("pass123")).thenReturn("hashed");
        when(userStore.save(any(User.class))).thenReturn(savedUser);

        User result = authService.addUser(tenantId, "new@acme.com", "New User", "pass123");

        assertNotNull(result);
        assertEquals(savedUser.getId(), result.getId());
        verify(userStore).existsByEmail(tenantId, "new@acme.com");
        verify(userStore).save(any(User.class));
    }

    @Test
    void addUser_duplicate() {
        UUID tenantId = UUID.randomUUID();

        when(userStore.existsByEmail(tenantId, "dup@acme.com")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.addUser(tenantId, "dup@acme.com", "Dup User", "pass"));

        assertTrue(ex.getMessage().contains("User already exists"));
        verify(userStore, never()).save(any());
    }
}
