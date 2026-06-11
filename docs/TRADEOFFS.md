# Tradeoffs — Pre-Decided Architectural Decisions

Every tradeoff in this document is FINAL. The agent must follow these decisions without deliberating. These reflect production best practices for a multi-tenant messaging system.

---

## 1. Consistency vs Availability

### Rule: Prefer availability for reads, consistency for writes.

| Operation | Choice | Why |
|-----------|--------|-----|
| Send message | **Consistent** — write must succeed or fail clearly | Messages must not be silently lost |
| Message history | **Available** — serve from DB even if slightly stale | Users tolerate 1-2 second staleness |
| Unread counts | **Eventually consistent** — Redis cache, best-effort | Off-by-one unread is acceptable |
| Membership check | **Consistent** — query DB (not cache) | Security-critical: wrong check = unauthorized access |
| Reaction counts | **Eventually consistent** — cache OK | Off-by-one reaction count is acceptable |
| Fan-out delivery | **Best-effort** — failure doesn't fail the write | Message is persisted; recipients catch up via sync |
| Typing indicators | **Fire-and-forget** — no persistence, no guarantee | Ephemeral by nature |
| Presence | **Eventually consistent** — 5s debounce acceptable | Flicker is worse than 5s delay |

### Read-Your-Own-Writes Guarantee
- After sending a message, the sender MUST see it in their channel history immediately.
- Implementation: The `sendMessage()` response returns the persisted message. Client appends it locally without waiting for WS event or re-fetching history.
- After editing a message, the sender MUST see the edit reflected immediately. Response returns updated message.

---

## 2. Denormalization Rules

### Rule: Denormalize for read-hot paths. Accept write cost.

| Field | Denormalized? | Why |
|-------|-------------|-----|
| `messages.sender_name` | **Yes** | History fetch is read-hot (100x more reads than writes). Avoids JOIN with users table on every history page. |
| `messages.reply_count` | **Yes** | Channel view shows reply count per message. Count query on every message is O(N). |
| `channels.member_count` | **Yes** | Channel list shows member count. Count query per channel is expensive. |
| `reaction counts` | **No — use aggregation** | Reactions change frequently. Denormalizing means updating message row on every react. Use `GROUP BY emoji` query or Redis cache. |
| `user.display_name` in messages | **Yes (sender_name)** | Already done. Stale name if user renames — acceptable tradeoff. |

### When to Denormalize
- Denormalize when: read frequency > 10x write frequency AND the join is expensive
- Don't denormalize when: the value changes frequently (reactions, presence, typing)
- Always use atomic SQL for counter updates: `SET count = count + 1` (not read-modify-write)

---

## 3. Sync vs Async

### Rule: Sync for MVP. The system is designed so async can be added later.

| Operation | Current | Scale Path |
|-----------|---------|-----------|
| Message fan-out | **Sync** (FanoutService called inline) | Kafka consumer (same interface) |
| Unread increment | **Sync** (Redis HINCRBY inline) | Same (Redis is fast enough) |
| Typing indicator | **Sync** (WS → fanout) | Same (ephemeral, low cost) |
| Reaction fan-out | **Sync** | Kafka consumer |
| Search indexing | **Sync** (PG query) | Async Elasticsearch indexer |
| Scheduled messages | **Async** (@Scheduled poller) | Already async |

### Fan-out Failure Handling
- If fan-out fails (Redis down, network error): **swallow the exception, log it, continue**.
- The message is already persisted. Recipients will get it via:
  1. WebSocket reconnection sync
  2. History API poll
  3. Unread count refresh
- NEVER let fan-out failure cause a 500 error on message send.

---

## 4. Caching Strategy

### Rule: Cache reads, not writes. Use Redis with TTL.

| What | Cached? | TTL | Invalidation |
|------|---------|-----|-------------|
| Membership check (isMember) | **No** | — | Security-critical, always hit DB |
| Member list (getMemberUserIds) | **Optional** | 60s | On member add/remove |
| Unread counts | **Yes** (Redis Hash) | 30 days | Reset on mark-read |
| Idempotency keys | **Yes** (Redis String) | 5 min | Auto-expire |
| Login attempts | **Yes** (Redis counter) | 15 min | Auto-expire |
| Rate limit counters | **Yes** (Redis counter) | 2s | Auto-expire |
| User profile | **No** | — | Low read frequency, DB is fine |
| Channel details | **No** | — | Changes are infrequent, DB is fine |
| Reaction counts | **Optional** (Redis Hash) | 5 min | On react/unreact |

### Cache Invalidation Rule
- **Never cache security-critical data** (membership, permissions, auth)
- **Always set TTL** — never cache indefinitely (prevents stale data accumulation)
- **Accept staleness** for display data (names, counts, presence)

---

## 5. Soft Delete vs Hard Delete

### Rule: Always soft delete. Never hard delete user data.

| Entity | Delete Strategy | Why |
|--------|----------------|-----|
| Messages | `is_deleted = true` | Thread integrity, audit trail, legal compliance |
| Channels | `is_archived = true` | Members may need history access |
| Users | `status = 'deactivated'` | Messages reference sender_id |
| Reactions | **Hard delete** (exception) | No audit need, unique constraint means re-adding is clean |
| Pins | **Hard delete** (exception) | Toggle action, no audit need |
| Stars | **Hard delete** (exception) | Personal bookmark, no audit need |

### Query Rule
- All list/history queries MUST filter `is_deleted = false` (or `is_archived = false`, `status = 'active'`)
- This is already the pattern. New features must follow it.

---

## 6. Pagination Strategy

### Rule: Cursor-based for chronological data. Offset-based for non-time-ordered lists.

| Data | Strategy | Cursor | Direction |
|------|----------|--------|-----------|
| Channel messages (history) | **Cursor** | `before` (Instant) | Newest first (DESC) |
| Thread replies | **Cursor** | `after` (Instant) | Oldest first (ASC) |
| Search results | **Cursor** | `before` (Instant) | Newest first (DESC) |
| Channel members | **Offset** | `offset` + `limit` | No order guarantee |
| Pinned messages | **Cursor** | `before` (Instant) | Newest pinned first |
| Starred items | **Cursor** | `before` (Instant) | Most recently starred first |
| Reaction users | **No pagination** | — | Fetch all (max 50 per emoji) |

### Pagination Defaults
- Default `limit`: 50
- Max `limit`: 100 (clamp in service, don't error)
- If `cursor` is null: return the most recent page

---

## 7. Authorization Model

### Rule: Check authorization in the SERVICE layer, not the handler.

| Operation | Who Can Do It | Check |
|-----------|--------------|-------|
| Create channel | Any authenticated user | Tenant membership (JWT is enough) |
| Delete/archive channel | Channel admin or tenant admin | `AuthorizationService.requireChannelAdminOrTenantAdmin()` |
| Add members | Channel admin or tenant admin | Same as above |
| Remove member (self) | Any member | `userId == targetUserId` |
| Remove member (other) | Channel admin or tenant admin | `AuthorizationService.requireChannelAdminOrTenantAdmin()` |
| Send message | Channel member | `channelService.isMember()` |
| Edit message | Message sender only | `message.senderId == currentUserId` |
| Delete message | Sender OR channel admin OR tenant admin | Check sender first, then admin |
| Pin message | Any channel member | `channelService.isMember()` |
| React to message | Any channel member | `channelService.isMember()` |
| Set channel topic | Any channel member | `channelService.isMember()` |
| Search | Scoped to member channels | Filter by user's channel list |
| View presence | Any tenant member | JWT tenant scope |
| Update own profile | Self only | `userId == currentUserId` |

### DM Authorization
- DMs cannot have members added/removed
- DMs cannot be archived
- DMs can have topic set
- DMs support all message operations (send, edit, delete, react, thread, pin)

---

## 8. Error Response Codes

### Rule: Use standard HTTP codes. Return `ApiResponse` wrapper.

| Scenario | HTTP Code | Error Message |
|----------|-----------|---------------|
| Validation failure | 400 | Specific field error |
| Invalid UUID format | 400 | "Invalid request format. Check field types." |
| Resource not found | 400 | "{Resource} not found" (NOT 404 — we use ApiResponse wrapper) |
| Not authenticated | 401 | "Missing or invalid Authorization header" |
| Not authorized | 403 | "Not a member of this channel" / "Admin access required" |
| Duplicate resource | 409 | "Already exists" (for reactions, unique constraints) |
| Rate limited | 429 | "Too many requests" |
| Server error | 500 | "An unexpected error occurred" (never expose internals) |

### Why 400 Instead of 404
Our `ApiResponse` wrapper always returns 200 with `success: false` for business errors. We use HTTP status codes for transport-level errors (401, 403, 429, 500) and 400 for all client input errors. This is consistent across the system — do NOT introduce 404s for "not found" business logic.

---

## 9. Transaction Boundaries

### Rule: One transaction per write operation. Read-only for reads.

| Operation | Transaction | Why |
|-----------|------------|-----|
| Send message | `@Transactional` | Persist + idempotency in one tx |
| Edit message | `@Transactional` | Single row update |
| Delete message | `@Transactional` | Single row update |
| Add members | `@Transactional` | Multiple member inserts + count update |
| Create channel | `@Transactional` | Channel + first member + count |
| Fan-out | **No transaction** | Redis operations, not DB |
| History query | `@Transactional(readOnly = true)` | Read optimization |
| Search | `@Transactional(readOnly = true)` | Read optimization |

### Rules
- NEVER wrap fan-out in the same transaction as the write (fan-out can fail independently)
- NEVER hold a transaction open during an inter-service REST call (can cause connection pool exhaustion)
- Always use `readOnly = true` for pure reads (Hibernate optimization)

---

## 10. WebSocket Event Delivery Semantics

### Rule: At-most-once delivery. Clients handle reconnection.

| Guarantee | Choice |
|-----------|--------|
| Delivery | **At-most-once** — if WS is disconnected, event is lost |
| Ordering | **Not guaranteed** across servers (single server preserves order) |
| Duplicates | **Possible** on reconnect sync (client must deduplicate by message ID) |
| Catch-up | **Client-initiated** — sync message with last-seen IDs |

### Why At-Most-Once
- Exactly-once requires message acknowledgment + retry + dedup = complex
- At-most-once + reconnection sync = simple + reliable enough
- Client already has message IDs for deduplication
- REST API is the source of truth; WS is the fast-path notification

---

## 11. Inter-Service Communication

### Rule: REST for queries, Redis Pub/Sub for events. No gRPC (MVP).

| Communication | Method | Why |
|---------------|--------|-----|
| message-service → channel-service (membership) | REST `/internal/*` | Synchronous, needed before processing |
| ws-gateway → channel-service (delivery check) | REST `/internal/*` | Synchronous, needed before delivery |
| ws-gateway → message-service (reconnect sync) | REST `/internal/*` | Synchronous, needed for sync |
| message-service → ws-gateway (new message) | Redis Pub/Sub | Async, best-effort, fan-out pattern |
| Any service → ws-gateway (any event) | Redis Pub/Sub | Same fan-out pattern |

### Failure Handling
- If channel-service is down when message-service checks membership: **throw SecurityException** (fail the request — membership is security-critical)
- If channel-service is down when ws-gateway checks delivery: **skip delivery** (best-effort — message already persisted)
- If Redis Pub/Sub fails: **swallow and log** (best-effort delivery)
