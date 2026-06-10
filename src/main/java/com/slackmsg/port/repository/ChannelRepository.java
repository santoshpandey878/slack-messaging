package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    Optional<Channel> findByIdAndTenantId(UUID id, UUID tenantId);

    // List channels the user is a member of
    @Query("SELECT c FROM Channel c JOIN ChannelMember cm ON c.id = cm.channelId " +
           "WHERE c.tenantId = :tenantId AND cm.userId = :userId AND c.isArchived = false " +
           "ORDER BY c.updatedAt DESC")
    List<Channel> findUserChannels(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE Channel c SET c.memberCount = c.memberCount + :delta, c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :channelId")
    void updateMemberCount(@Param("channelId") UUID channelId, @Param("delta") int delta);

    long countByTenantIdAndIsArchivedFalse(UUID tenantId);
}
