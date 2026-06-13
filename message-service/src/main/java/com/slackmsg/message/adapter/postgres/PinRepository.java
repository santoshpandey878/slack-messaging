package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.PinnedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PinRepository extends JpaRepository<PinnedMessage, UUID> {
    List<PinnedMessage> findByChannelIdOrderByCreatedAtDesc(UUID channelId);
    Optional<PinnedMessage> findByChannelIdAndMessageId(UUID channelId, UUID messageId);
    void deleteByChannelIdAndMessageId(UUID channelId, UUID messageId);
    long countByChannelId(UUID channelId);
}
