package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.Message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for message persistence.
 *
 * This is the SWAPPABLE interface — the core architectural boundary.
 * Business logic depends on THIS interface, not on JPA or Cassandra.
 *
 * MVP:   PostgresMessageStore (uses JPA underneath)
 * Scale: CassandraMessageStore (swap via config, zero business logic change)
 */
public interface MessageStore {

    Message save(Message message);

    Optional<Message> findById(UUID tenantId, UUID messageId);

    /**
     * Get latest messages for a channel (cursor-based pagination).
     * If cursor is null, returns most recent messages.
     */
    List<Message> getHistory(UUID tenantId, UUID channelId, Instant beforeCursor, int limit);

    /**
     * Get messages AFTER a specific message ID (for reconnection sync).
     */
    List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit);

    /**
     * Check if a message with this idempotency key already exists.
     */
    boolean existsByIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<Message> getThreadReplies(UUID tenantId, UUID channelId, UUID parentMessageId, Instant afterCursor, int limit);

    void incrementReplyCount(UUID messageId);
}
