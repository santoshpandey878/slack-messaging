package com.slackmsg.adapter.postgres.repo;

import com.slackmsg.domain.entity.Tenant;
import com.slackmsg.port.repository.TenantRepository;
import com.slackmsg.port.repository.TenantStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresTenantStore implements TenantStore {

    private final TenantRepository jpaRepo;

    @Override
    public Tenant save(Tenant tenant) {
        return jpaRepo.save(tenant);
    }

    @Override
    public Optional<Tenant> findById(UUID id) {
        return jpaRepo.findById(id);
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return jpaRepo.findBySlug(slug);
    }

    @Override
    public boolean existsBySlug(String slug) {
        return jpaRepo.existsBySlug(slug);
    }
}
