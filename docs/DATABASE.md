# Database

## Overview

- **Engine:** PostgreSQL 14
- **Shared:** All services connect to the same database (`slackmsg`)
- **Migrations:** Flyway, identical copies in all 5 services (first to start runs them)
- **Credentials:** `slackuser`/`slackpass` (Docker default)

## Current Schema (V1-V4)

### tenants
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | gen_random_uuid() |
| name | VARCHAR(255) NOT NULL | |
| slug | VARCHAR(100) UNIQUE NOT NULL | URL-safe identifier |
| plan | VARCHAR(50) DEFAULT 'standard' | |
| status | VARCHAR(20) DEFAULT 'active' | |
| max_users | INT DEFAULT 10000 | |
| max_channels | INT DEFAULT 1000 | |
| created_at | TIMESTAMPTZ DEFAULT NOW() | |

### users
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK→tenants | |
| email | VARCHAR(255) NOT NULL | UNIQUE(tenant_id, email) |
| display_name | VARCHAR(255) NOT NULL | |
| password_hash | VARCHAR(255) NOT NULL | BCrypt |
| role | VARCHAR(20) DEFAULT 'member' | member, admin |
| status | VARCHAR(20) DEFAULT 'active' | |
| avatar_url | VARCHAR(2048) | V4 — nullable |
| status_text | VARCHAR(255) | V4 — nullable |
| timezone | VARCHAR(50) | V4 — nullable |
| created_at | TIMESTAMPTZ | |

**Indexes:** idx_users_tenant(tenant_id)

### channels
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| tenant_id | UUID FK→tenants | |
| name | VARCHAR(255) | NULL for DMs |
| type | VARCHAR(20) NOT NULL | PUBLIC, PRIVATE, DM |
| created_by | UUID FK→users ON DELETE SET NULL | |
| is_archived | BOOLEAN DEFAULT FALSE | |
| member_count | INT DEFAULT 0 | Denormalized |
| topic | TEXT | V4 — nullable |
| description | TEXT | V4 — nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**Indexes:** idx_channels_tenant, idx_channels_tenant_active

### channel_members
| Column | Type | Notes |
|--------|------|-------|
| channel_id | UUID PK (composite) | FK→channels CASCADE |
| user_id | UUID PK (composite) | FK→users CASCADE |
| role | VARCHAR(20) DEFAULT 'MEMBER' | ADMIN, MEMBER |
| joined_at | TIMESTAMPTZ | |
| last_read_msg_id | UUID | For unread tracking |
| last_read_at | TIMESTAMPTZ | |
| muted | BOOLEAN DEFAULT FALSE | V4 |
| notification_level | VARCHAR(20) DEFAULT 'default' | V4 |

**Indexes:** idx_members_user(user_id)

### messages
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| tenant_id | UUID NOT NULL | NOT FK (Cassandra-ready) |
| channel_id | UUID NOT NULL | NOT FK |
| sender_id | UUID NOT NULL | NOT FK |
| content | TEXT | NULL for media-only |
| message_type | VARCHAR(20) DEFAULT 'TEXT' | TEXT, MEDIA, SYSTEM |
| sender_name | VARCHAR(255) | Denormalized |
| media_url | TEXT | |
| media_type | VARCHAR(100) | |
| is_deleted | BOOLEAN DEFAULT FALSE | Soft delete |
| idempotency_key | VARCHAR(255) | |
| parent_message_id | UUID | V4 — thread parent |
| reply_count | INT DEFAULT 0 | V4 — denormalized |
| edited_at | TIMESTAMPTZ | V4 — null if never edited |
| created_at | TIMESTAMPTZ | |

**Indexes:** idx_messages_channel_time, idx_messages_idempotency, idx_messages_sender, idx_messages_parent, idx_messages_thread

**Design:** No foreign keys on tenant_id/channel_id/sender_id — intentional for future Cassandra swap.

### dm_pairs
| Column | Type | Notes |
|--------|------|-------|
| tenant_id | UUID PK (composite) | |
| user_id_1 | UUID PK (composite) | Sorted (smaller UUID first) |
| user_id_2 | UUID PK (composite) | |
| channel_id | UUID FK→channels CASCADE | |

**Indexes:** idx_dm_pairs_reverse(tenant_id, user_id_2, user_id_1)

### reactions (V4)
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| tenant_id | UUID NOT NULL | |
| channel_id | UUID NOT NULL | |
| message_id | UUID NOT NULL | |
| user_id | UUID NOT NULL | |
| emoji | VARCHAR(100) NOT NULL | |
| created_at | TIMESTAMPTZ | |

**Constraints:** UNIQUE(message_id, user_id, emoji)
**Indexes:** idx_reactions_message, idx_reactions_tenant

### pinned_messages (V4)
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| tenant_id | UUID NOT NULL | |
| channel_id | UUID NOT NULL | |
| message_id | UUID NOT NULL | |
| pinned_by | UUID NOT NULL | |
| created_at | TIMESTAMPTZ | |

**Constraints:** UNIQUE(channel_id, message_id)

### starred_items (V4)
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| tenant_id | UUID NOT NULL | |
| user_id | UUID NOT NULL | |
| item_type | VARCHAR(50) NOT NULL | "message", "channel", "file" |
| item_id | UUID NOT NULL | |
| channel_id | UUID | Optional context |
| created_at | TIMESTAMPTZ | |

**Constraints:** UNIQUE(user_id, item_type, item_id)

## How to Add a New Migration

1. Find the latest version: `ls auth-service/src/main/resources/db/migration/`
2. Create: `V{N+1}__description.sql` (double underscore after version)
3. Copy to ALL 5 services:
```bash
SRC="auth-service/src/main/resources/db/migration/V5__my_change.sql"
for svc in channel-service message-service media-service ws-gateway; do
  cp "$SRC" "$svc/src/main/resources/db/migration/"
done
```
4. Always use `IF NOT EXISTS` for idempotency
5. Always include `tenant_id` for multi-tenancy
6. Restart services to apply (Flyway runs on boot)

## Entity Pattern

All entities go in `common/src/main/java/com/slackmsg/domain/entity/`.

**Template:**
```java
@Entity
@Table(name = "table_name")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EntityName {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    // ... fields ...

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

**Imports:** Always `javax.persistence.*` (NOT `jakarta.*`).

## Repository Pattern

Repositories go in the owning service: `{service}/src/main/java/com/slackmsg/{service}/adapter/postgres/`

```java
public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {
    List<Bookmark> findByChannelIdOrderByCreatedAtDesc(UUID channelId);
}
```
