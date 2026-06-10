package com.slackmsg.adapter.postgres.repo;

import com.slackmsg.domain.entity.User;
import com.slackmsg.port.repository.UserRepository;
import com.slackmsg.port.repository.UserStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresUserStore implements UserStore {

    private final UserRepository jpaRepo;

    @Override
    public User save(User user) {
        return jpaRepo.save(user);
    }

    @Override
    public Optional<User> findById(UUID tenantId, UUID userId) {
        return jpaRepo.findByIdAndTenantId(userId, tenantId);
    }

    @Override
    public Optional<User> findByEmail(UUID tenantId, String email) {
        return jpaRepo.findByEmailAndTenantId(email, tenantId);
    }

    @Override
    public List<User> findByTenant(UUID tenantId) {
        return jpaRepo.findByTenantId(tenantId);
    }

    @Override
    public boolean existsByEmail(UUID tenantId, String email) {
        return jpaRepo.existsByEmailAndTenantId(email, tenantId);
    }
}
