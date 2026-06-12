package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.message.adapter.postgres.MessageRepository;
import com.slackmsg.port.repository.MessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of MessageStore.
 * Uses Spring Data JPA underneath.
 *
 * To swap to Cassandra: create CassandraMessageStore implementing MessageStore.
 * Business logic (MessageService) stays untouched.
 */
@Component
@RequiredArgsConstructor
public class PostgresMessageStore implements MessageStore {

    private final MessageRepository jpaRepo;

    @Override
    public Message save(Message message) {
        return jpaRepo.save(message);
    }

    @Override
    public Optional<Message> findById(UUID tenantId, UUID messageId) {
        return jpaRepo.findById(messageId)
                .filter(m -> m.getTenantId().equals(tenantId));
    }

    @Override
    public List<Message> getHistory(UUID tenantId, UUID channelId, Instant beforeCursor, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        if (beforeCursor == null) {
            return jpaRepo.findLatestByChannel(tenantId, channelId, page);
        }
        return jpaRepo.findByChannelBeforeCursor(tenantId, channelId, beforeCursor, page);
    }

    @Override
    public List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit) {
        // Validate that afterMessageId exists — if not, the subquery returns NULL
        // and the comparison `createdAt > NULL` silently returns empty list
        boolean exists = jpaRepo.findById(afterMessageId)
                .filter(m -> m.getTenantId().equals(tenantId) && m.getChannelId().equals(channelId))
                .isPresent();
        if (!exists) {
            // afterMessageId not found — return empty (client has stale cursor)
            return java.util.Collections.emptyList();
        }
        return jpaRepo.findMessagesAfter(tenantId, channelId, afterMessageId, PageRequest.of(0, limit));
    }

    @Override
    public boolean existsByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jpaRepo.existsByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
    }
}
