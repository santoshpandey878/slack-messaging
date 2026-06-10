package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for user persistence.
 */
public interface UserStore {

    User save(User user);
    Optional<User> findById(UUID tenantId, UUID userId);
    Optional<User> findByEmail(UUID tenantId, String email);
    List<User> findByTenant(UUID tenantId);
    boolean existsByEmail(UUID tenantId, String email);
}
