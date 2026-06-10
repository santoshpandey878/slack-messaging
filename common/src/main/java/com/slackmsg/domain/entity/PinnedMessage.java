package com.slackmsg.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pinned_messages", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"channel_id", "message_id"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PinnedMessage {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "pinned_by", nullable = false)
    private UUID pinnedBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
