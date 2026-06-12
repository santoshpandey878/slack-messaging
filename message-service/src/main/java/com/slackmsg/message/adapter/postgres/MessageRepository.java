package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.createdAt < :cursor AND m.isDeleted = false AND m.parentMessageId IS NULL " +
           "ORDER BY m.createdAt DESC")
    List<Message> findByChannelBeforeCursor(@Param("tenantId") UUID tenantId,
            @Param("channelId") UUID channelId, @Param("cursor") Instant cursor, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.isDeleted = false AND m.parentMessageId IS NULL ORDER BY m.createdAt DESC")
    List<Message> findLatestByChannel(@Param("tenantId") UUID tenantId,
            @Param("channelId") UUID channelId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.createdAt > (SELECT m2.createdAt FROM Message m2 WHERE m2.id = :afterMessageId) " +
           "AND m.isDeleted = false ORDER BY m.createdAt ASC")
    List<Message> findMessagesAfter(@Param("tenantId") UUID tenantId,
            @Param("channelId") UUID channelId, @Param("afterMessageId") UUID afterMessageId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.parentMessageId = :parentId AND m.isDeleted = false ORDER BY m.createdAt ASC")
    List<Message> findThreadReplies(@Param("tenantId") UUID tenantId, @Param("channelId") UUID channelId,
            @Param("parentId") UUID parentId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.tenantId = :tenantId AND m.channelId = :channelId " +
           "AND m.parentMessageId = :parentId AND m.createdAt > :afterCursor " +
           "AND m.isDeleted = false ORDER BY m.createdAt ASC")
    List<Message> findThreadRepliesAfterCursor(@Param("tenantId") UUID tenantId, @Param("channelId") UUID channelId,
            @Param("parentId") UUID parentId, @Param("afterCursor") Instant afterCursor, Pageable pageable);

    @Modifying
    @Query("UPDATE Message m SET m.replyCount = m.replyCount + 1 WHERE m.id = :id")
    void incrementReplyCount(@Param("id") UUID id);

    boolean existsByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);
}
