package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // Cursor-based pagination: get messages before a given timestamp
    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.createdAt < :cursor AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findByChannelBeforeCursor(
            @Param("tenantId") UUID tenantId,
            @Param("channelId") UUID channelId,
            @Param("cursor") Instant cursor,
            Pageable pageable);

    // Get latest messages (no cursor)
    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findLatestByChannel(
            @Param("tenantId") UUID tenantId,
            @Param("channelId") UUID channelId,
            Pageable pageable);

    // Get messages AFTER a given ID (for reconnection sync)
    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.createdAt > (SELECT m2.createdAt FROM Message m2 WHERE m2.id = :afterMessageId) " +
           "AND m.isDeleted = false ORDER BY m.createdAt ASC")
    List<Message> findMessagesAfter(
            @Param("tenantId") UUID tenantId,
            @Param("channelId") UUID channelId,
            @Param("afterMessageId") UUID afterMessageId,
            Pageable pageable);

    // Check idempotency
    boolean existsByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);
}
