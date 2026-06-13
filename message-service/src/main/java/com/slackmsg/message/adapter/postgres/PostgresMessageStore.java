package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.port.repository.MessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresMessageStore implements MessageStore {

    private final MessageRepository jpaRepo;

    @Override
    public Message save(Message message) { return jpaRepo.save(message); }

    @Override
    public Optional<Message> findById(UUID tenantId, UUID messageId) {
        return jpaRepo.findById(messageId).filter(m -> m.getTenantId().equals(tenantId));
    }

    @Override
    public List<Message> getHistory(UUID tenantId, UUID channelId, Instant beforeCursor, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return beforeCursor == null ? jpaRepo.findLatestByChannel(tenantId, channelId, page)
                : jpaRepo.findByChannelBeforeCursor(tenantId, channelId, beforeCursor, page);
    }

    @Override
    public List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit) {
        boolean exists = jpaRepo.findById(afterMessageId)
                .filter(m -> m.getTenantId().equals(tenantId) && m.getChannelId().equals(channelId)).isPresent();
        return exists ? jpaRepo.findMessagesAfter(tenantId, channelId, afterMessageId, PageRequest.of(0, limit))
                : Collections.emptyList();
    }

    @Override
    public boolean existsByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jpaRepo.existsByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
    }

    @Override
    public List<Message> getThreadReplies(UUID tenantId, UUID channelId, UUID parentMessageId, int limit) {
        return jpaRepo.findThreadReplies(tenantId, channelId, parentMessageId, PageRequest.of(0, limit));
    }

    @Override
    public void incrementReplyCount(UUID parentMessageId) { jpaRepo.incrementReplyCount(parentMessageId); }

    @Override
    public List<Message> searchMessages(UUID tenantId, List<UUID> channelIds, String query, int limit) {
        if (channelIds == null || channelIds.isEmpty()) return Collections.emptyList();
        return jpaRepo.searchMessages(tenantId, channelIds, query, PageRequest.of(0, limit));
    }
}
