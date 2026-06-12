package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Reaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReactionRepository extends JpaRepository<Reaction, UUID> {

    List<Reaction> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    Optional<Reaction> findByMessageIdAndUserIdAndEmoji(UUID messageId, UUID userId, String emoji);

    void deleteByMessageIdAndUserIdAndEmoji(UUID messageId, UUID userId, String emoji);
}
