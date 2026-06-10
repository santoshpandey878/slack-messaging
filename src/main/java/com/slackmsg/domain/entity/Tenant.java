package com.slackmsg.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Builder.Default
    @Column(nullable = false)
    private String plan = "standard";

    @Builder.Default
    @Column(nullable = false)
    private String status = "active";

    @Builder.Default
    @Column(name = "max_users")
    private Integer maxUsers = 10000;

    @Builder.Default
    @Column(name = "max_channels")
    private Integer maxChannels = 1000;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
