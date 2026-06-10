package com.slackmsg.domain.entity;

import lombok.*;

import javax.persistence.*;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "dm_pairs")
@IdClass(DmPair.DmPairId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DmPair {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "user_id_1", nullable = false)
    private UUID userId1;

    @Id
    @Column(name = "user_id_2", nullable = false)
    private UUID userId2;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DmPairId implements Serializable {
        private UUID tenantId;
        private UUID userId1;
        private UUID userId2;
    }
}
