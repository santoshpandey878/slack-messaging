package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.DmPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DmPairRepository extends JpaRepository<DmPair, DmPair.DmPairId> {

    Optional<DmPair> findByTenantIdAndUserId1AndUserId2(UUID tenantId, UUID userId1, UUID userId2);
}
