package com.slackmsg.auth.adapter.postgres;

import com.slackmsg.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndTenantId(String email, UUID tenantId);

    Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);

    List<User> findByTenantId(UUID tenantId);

    boolean existsByEmailAndTenantId(String email, UUID tenantId);
}
