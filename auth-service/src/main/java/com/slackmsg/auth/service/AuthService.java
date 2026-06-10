package com.slackmsg.auth.service;

import com.slackmsg.domain.entity.Tenant;
import com.slackmsg.domain.entity.User;
import com.slackmsg.dto.request.LoginRequest;
import com.slackmsg.dto.request.RegisterRequest;
import com.slackmsg.dto.response.AuthResponse;
import com.slackmsg.port.repository.TenantStore;
import com.slackmsg.port.repository.UserStore;
import com.slackmsg.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Auth business logic — clean, focused.
 * Delegates: password → PasswordService, login guard → LoginGuardService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final TenantStore tenantStore;
    private final UserStore userStore;
    private final JwtUtil jwtUtil;
    private final PasswordService passwordService;
    private final LoginGuardService loginGuard;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (tenantStore.existsBySlug(req.getTenantSlug())) {
            throw new IllegalArgumentException("Tenant slug already exists: " + req.getTenantSlug());
        }

        Tenant tenant = tenantStore.save(Tenant.builder()
                .name(req.getTenantName()).slug(req.getTenantSlug()).build());

        User user = createUser(tenant.getId(), req.getEmail(), req.getDisplayName(), req.getPassword(), "admin");

        log.info("Tenant registered: slug={} tenantId={} adminId={}", req.getTenantSlug(), tenant.getId(), user.getId());
        return buildAuthResponse(user, tenant.getId());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        loginGuard.checkLocked(req.getTenantSlug(), req.getEmail());

        Tenant tenant = tenantStore.findBySlug(req.getTenantSlug())
                .orElseThrow(() -> loginFailed(req, "tenant not found"));

        User user = userStore.findByEmail(tenant.getId(), req.getEmail())
                .orElseThrow(() -> loginFailed(req, "user not found"));

        if (!passwordService.matches(req.getPassword(), user.getPasswordHash())) {
            throw loginFailed(req, "wrong password");
        }

        if (!"active".equals(user.getStatus())) {
            log.warn("Login failed (deactivated): userId={}", user.getId());
            throw new IllegalArgumentException("User account is deactivated");
        }

        loginGuard.clearAttempts(req.getTenantSlug(), req.getEmail());
        log.info("Login successful: userId={} tenantId={}", user.getId(), tenant.getId());
        return buildAuthResponse(user, tenant.getId());
    }

    @Transactional
    public User addUser(UUID tenantId, String email, String displayName, String password) {
        if (userStore.existsByEmail(tenantId, email)) {
            throw new IllegalArgumentException("User already exists with email: " + email);
        }
        try {
            User user = createUser(tenantId, email, displayName, password, "member");
            log.info("User added: userId={} email={} tenantId={}", user.getId(), email, tenantId);
            return user;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("User already exists with email: " + email);
        }
    }

    // ═══ Private helpers ═══

    private User createUser(UUID tenantId, String email, String displayName, String password, String role) {
        return userStore.save(User.builder()
                .tenantId(tenantId).email(email).displayName(displayName)
                .passwordHash(passwordService.encode(password)).role(role).build());
    }

    private AuthResponse buildAuthResponse(User user, UUID tenantId) {
        String token = jwtUtil.generateToken(user.getId(), tenantId, user.getRole(), user.getDisplayName());
        return AuthResponse.builder()
                .token(token).userId(user.getId()).tenantId(tenantId)
                .displayName(user.getDisplayName()).role(user.getRole()).build();
    }

    private IllegalArgumentException loginFailed(LoginRequest req, String reason) {
        loginGuard.recordFailedAttempt(req.getTenantSlug(), req.getEmail());
        log.warn("Login failed ({}): email={} slug={}", reason, req.getEmail(), req.getTenantSlug());
        return new IllegalArgumentException("Invalid credentials");
    }
}
