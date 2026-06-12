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
        if (!exists) return Collections.emptyList();
        return jpaRepo.findMessagesAfter(tenantId, channelId, afterMessageId, PageRequest.of(0, limit));
    }

    @Override
    public boolean existsByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jpaRepo.existsByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
    }

    @Override
    public List<Message> getThreadReplies(UUID tenantId, UUID channelId, UUID parentMessageId,
                                           Instant afterCursor, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return afterCursor == null ? jpaRepo.findThreadReplies(tenantId, channelId, parentMessageId, page)
                : jpaRepo.findThreadRepliesAfterCursor(tenantId, channelId, parentMessageId, afterCursor, page);
    }

    @Override
    public void incrementReplyCount(UUID messageId) { jpaRepo.incrementReplyCount(messageId); }
}
