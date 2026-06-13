# Edge Cases & Hot Issues

Every edge case in this document MUST be handled. The agent must consult this before writing any feature code.

---

## 1. Race Conditions

### Concurrent Message Send (Same Idempotency Key)
- **Scenario:** Client retries send while first request is still processing.
- **Solution:** Idempotency key check in Redis (5-min TTL). First write wins. Second request gets cached response.
- **Pattern:** `idempotencyService.checkDuplicate()` → if found, return cached; else persist + cache.

### Concurrent Member Add
- **Scenario:** Two admins add the same user simultaneously.
- **Solution:** UNIQUE constraint on `(channel_id, user_id)`. Catch `DataIntegrityViolationException`, skip silently.
- **Increment:** Only increment `member_count` for successful inserts. Use `added` counter.

### Concurrent DM Creation
- **Scenario:** Both users create DM with each other at the same time.
- **Solution:** DmPair table with sorted user IDs (smaller UUID first). UNIQUE constraint catches duplicate. Catch exception, look up existing channel.

### Concurrent Reaction Toggle
- **Scenario:** User double-clicks react button, sending add+add or add+remove simultaneously.
- **Solution:** UNIQUE constraint on `(message_id, user_id, emoji)`. Catch exception on duplicate add. Remove is idempotent.

### Concurrent Reply Count Update
- **Scenario:** Two users reply to the same thread parent at the same time.
- **Solution:** Atomic SQL: `UPDATE messages SET reply_count = reply_count + 1 WHERE id = :parentId`. Never read-modify-write.

### Concurrent Channel Topic Update
- **Scenario:** Two users update topic at the same time.
- **Solution:** Last write wins. No conflict resolution needed for chat metadata.

---

## 2. Null & Missing Data

### Nullable Fields in Entities
| Field | When Null | Handle |
|-------|-----------|--------|
| `message.content` | Media-only message | Display media without text |
| `message.media_url` | Text-only message | Display text without media |
| `message.sender_name` | Legacy message before V3 | Show "Unknown" |
| `message.parent_message_id` | Top-level message | Not a thread reply |
| `message.edited_at` | Never edited | Don't show "(edited)" |
| `channel.name` | DM channel | Enrich with other user's name |
| `channel.topic` | Not set | Show empty |
| `channel.description` | Not set | Show empty |
| `user.avatar_url` | No avatar uploaded | Show default avatar |
| `user.status_text` | No status | Show nothing |
| `user.timezone` | Not set | Use UTC default |
| `channel_member.last_read_msg_id` | Never read | All messages are unread |

### JWT Claims
- `userId`, `tenantId`: MUST be present. Throw `SecurityException` if null.
- `role`: Default to "member" if missing.
- `displayName`: Default to "Unknown" if missing. Never NPE.

### Request Body Fields
- Always use `@Valid` on `@RequestBody` — Spring validates before handler executes.
- Optional fields: check for null before using. Never call `.toString()` on nullable UUID.

---

## 3. Deletion Cascades

### When a User is Deactivated
- **Messages remain** (soft reference via `sender_id`, no FK)
- **Channel memberships remain** but user can't log in
- **DM pairs remain** (other user still sees DM in sidebar)
- **Scheduled messages:** mark as `CANCELLED`
- **Do NOT cascade-delete** anything on user deactivation

### When a Channel is Archived
- **Messages remain** accessible (read-only)
- **Members remain** but can't send messages
- **Pins remain** visible
- **Block:** message send, member add/remove, topic change
- **Allow:** read history, read pins, read members

### When a Message is Soft-Deleted
- **Thread replies remain** (thread continues even with deleted parent)
- **Reactions remain** in DB (hidden because message is filtered out)
- **Pins:** if message was pinned, the pin record remains but pin list should filter it
- **Stars:** starred deleted message shows "[deleted]"
- **Search:** excluded (filter `is_deleted = false`)

---

## 4. Security Edge Cases

### Cross-Tenant Access
- **Every query MUST include `tenant_id`**. No exceptions.
- A user with valid JWT for tenant A must NEVER access tenant B data.
- **Internal endpoints** (`/internal/*`): no JWT validation, but they're only called by services within the Docker network. Do NOT expose to public.

### JWT Expiry During Operation
- JWT expires mid-request: request completes normally (JWT was valid at entry).
- JWT expires mid-WebSocket: WS stays connected until next auth check (reconnect).
- Client responsibility to refresh before expiry.

### Admin Impersonation
- A tenant admin can add/remove users, create channels, delete any message.
- A tenant admin CANNOT: edit another user's message, access other tenants.
- Channel admins: can manage members of that channel only.

### Rate Limit Bypass Attempts
- Rate limiting is per tenant+user (Redis). Changing IP doesn't bypass it.
- Login lockout is per email+tenant (Redis). Different IP still gets locked.
- No rate limiting on `/internal/*` (service-to-service).

---

## 5. Pagination Edge Cases

### Empty Results
- If no messages in channel: return empty list `[]`, not null, not 404.
- If cursor is beyond all data: return empty list.

### First Page (No Cursor)
- If `before` cursor is null: return the most recent page.
- If `after` cursor is null: return the oldest page (for threads).

### Invalid Cursor
- If `before` timestamp is malformed: return 400.
- If `afterMessageId` doesn't exist: return empty list (don't error).

### Limit Bounds
- Clamp to `[1, 100]`: `limit = Math.max(1, Math.min(limit, 100))`.
- Never error on out-of-range limit — silently clamp.

### Concurrent Modifications During Pagination
- New messages may appear between pages. This is acceptable (client fetches latest on refresh).
- Deleted messages disappear from future pages. No tombstone needed.

---

## 6. WebSocket Edge Cases

### Multiple Tabs / Devices
- Same user can have multiple WebSocket connections from different tabs.
- Each gets its own session in `WsSessionManager`.
- Fan-out delivers to ALL sessions of the same user (they're on the same server).
- Presence: only mark offline when ALL connections are closed.

### Reconnection After Long Offline
- Client sends sync with last-seen message IDs.
- Server returns up to 100 messages per channel.
- If more than 100 missed: client should fetch remaining via REST history API.
- Sync only covers messages — not reactions, edits, presence changes.

### Invalid Token on WS Connect
- Close with `POLICY_VIOLATION` status.
- Do NOT register the session.

### Message Delivery to Disconnected Session
- `IOException` when sending → catch, close session, unregister.
- Message is not retried (at-most-once delivery).

### WS Message Rate Limiting
- Client can spam ping/typing messages.
- For typing: client should throttle to 1 event per 3 seconds.
- Server should ignore rapid-fire typing events (debounce in WsHandler if needed).

---

## 7. Data Integrity Edge Cases

### Orphaned Data
- Reactions on deleted messages: hidden (message filtered out). No cleanup needed.
- Pins on deleted messages: filter in pin list query (`JOIN messages WHERE is_deleted = false`).
- Stars on deleted messages: show "[deleted]" placeholder.
- Scheduled messages for deactivated users: mark `CANCELLED` in poller.

### Stale Denormalized Data
- `sender_name` on message: stale if user changes display name. Acceptable — not worth the update cost.
- `member_count` on channel: could drift if increment/decrement errors. Acceptable — can be recalculated.
- `reply_count` on message: could drift on concurrent thread delete. Use `reply_count = GREATEST(reply_count - 1, 0)` to prevent negative.

### Large Channels (10K members)
- `getMemberUserIds(channelId)` returns 10K UUIDs — memory concern.
- Fan-out iterates all members — latency concern.
- **Solution for MVP:** Accept the cost. For scale: paginated fan-out, batch Redis operations.
- **@channel/@here mentions:** Block in channels > 1000 members unless sender is admin.

---

## 8. Media Edge Cases

### Upload URL Expired Before Use
- Presigned URL has 60-min expiry. If client doesn't use it, URL expires silently.
- Client should request new URL and retry.

### Uploaded File But Never Sent Message
- Orphaned file in S3/MinIO. No cleanup mechanism in MVP.
- For scale: background job that scans for unreferenced media keys older than 24 hours.

### Content-Type Mismatch
- Client requests URL for "image/jpeg" but uploads "application/exe".
- MinIO/S3 enforces the content-type from the presigned URL. Mismatch = upload fails.

### Presigned URL Hostname Mismatch (CRITICAL — caused production bug)
- **Scenario:** Presigned URLs generated with Docker-internal hostname (`minio:9000`). Browser can't reach Docker hostnames.
- **Symptom:** `SignatureDoesNotMatch` error when opening/uploading files. Images don't load.
- **Root cause:** Presigned URL signature includes the hostname. Rewriting hostname client-side breaks the signature.
- **Solution:** Use separate `STORAGE_PUBLIC_ENDPOINT` config for URLs sent to browser. Generate presigned URLs with the public endpoint so signature matches.
- **Rule:** NEVER rewrite presigned URL hostnames in the frontend. Fix at the source (backend config).

---

## 9. Unread Count Edge Cases

### Mark-Read with Null Fields
- **Scenario:** Frontend calls `POST /read` with `{lastReadMessageId: null}`. Backend has `@NotNull` validation.
- **Symptom:** Mark-read silently fails. Unread badge never clears. User sees stale count on active channel.
- **Solution:** Make `lastReadMessageId` optional. UnreadService only needs channelId to reset count.
- **Rule:** Never use `@NotNull` on DTO fields that the frontend may not populate. Test API with null optionals.

### Unread on Active Channel
- **Scenario:** User has channel open. Another user sends message. Unread count shows on the active channel.
- **Solution:** `selectChannel()` must `await` the mark-read API before refreshing channel list. Active channel never shows badge regardless of Redis state.

---

## 10. Multi-Service Failure Scenarios

| Failure | Impact | Handling |
|---------|--------|----------|
| **PostgreSQL down** | All writes fail | Services return 500. Reconnect on recovery. |
| **Redis down** | Fan-out fails, rate limiting disabled, unread stale | Messages still persist (DB). Fan-out swallowed. Rate limit filter skips (fail open). |
| **channel-service down** | message-service can't check membership | `ChannelServiceClient` throws `SecurityException` → message send fails with 500. This is correct — membership is security-critical. |
| **channel-service down** | ws-gateway can't check delivery | Delivery silently skipped. Message already persisted, will sync on reconnect. |
| **message-service down** | ws-gateway can't sync on reconnect | Sync returns empty. Client falls back to REST history after connection. |
| **MinIO down** | Media uploads fail | Presigned URL generation fails → 500. Text messages still work. |
| **Redis Pub/Sub down** | No real-time delivery | Messages persist. Zero real-time until Redis recovers. Clients sync on reconnect. |
