package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.Tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for tenant persistence.
 */
public interface TenantStore {

    Tenant save(Tenant tenant);
    Optional<Tenant> findById(UUID id);
    Optional<Tenant> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
