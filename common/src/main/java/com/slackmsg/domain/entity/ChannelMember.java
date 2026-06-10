package com.slackmsg.domain.entity;

import com.slackmsg.domain.enums.MemberRole;
import lombok.*;

import javax.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_members")
@IdClass(ChannelMember.ChannelMemberId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChannelMember {

    @Id
    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private MemberRole role = MemberRole.MEMBER;

    @Builder.Default
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "last_read_msg_id")
    private UUID lastReadMsgId;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    // Notification preferences per member per channel
    @Builder.Default
    @Column(name = "muted")
    private Boolean muted = false;

    @Builder.Default
    @Column(name = "notification_level")
    private String notificationLevel = "default";

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ChannelMemberId implements Serializable {
        private UUID channelId;
        private UUID userId;
    }
}
