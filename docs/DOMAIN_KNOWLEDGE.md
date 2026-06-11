# Domain Knowledge — Slack Feature Catalog

This document defines what each Slack feature IS, how it behaves, edge cases, and exactly what to build. The agent MUST read this before implementing any feature to understand the domain semantics — not just the API shape.

---

## Threads / Replies

### What It Is
A thread is a sub-conversation attached to a parent message. Users reply to a specific message, creating a nested discussion without cluttering the main channel timeline.

### Slack Behavior
- Any message in a channel can become a thread parent (first reply creates the thread)
- Thread replies appear in a side panel, NOT in the main channel timeline
- Parent message shows reply count + avatars of participants in the channel view
- "Also send to channel" option: a thread reply can ALSO appear in the main timeline (dual visibility)
- Thread participants are tracked (anyone who replies or is @mentioned in thread)
- Notifications: only thread participants get notified of new replies (not all channel members)
- Thread parent can be in any channel type (public, private, DM)

### Data Model
- `messages.parent_message_id` — NULL for top-level, set for replies
- `messages.reply_count` — denormalized on parent message (increment on each reply)
- Thread replies are regular messages with `parent_message_id` set
- Do NOT create a separate "threads" table — threads are implicit (any message with replies)

### API Endpoints
- `POST /api/v1/channels/{channelId}/messages` with `parentMessageId` in body — send thread reply
- `GET /api/v1/channels/{channelId}/threads/{messageId}` — get thread replies (oldest first, unlike channel which is newest first)
- `GET /api/v1/channels/{channelId}/messages` — channel timeline excludes thread replies (only show parent messages)

### Edge Cases
- **Reply to deleted parent:** Allow it — the thread continues even if parent is soft-deleted. Show "[deleted]" for parent.
- **Reply to a reply:** NOT allowed. `parentMessageId` must point to a top-level message (where `parent_message_id IS NULL`). Validate this.
- **reply_count race condition:** Use `UPDATE messages SET reply_count = reply_count + 1 WHERE id = :parentId` (atomic SQL increment, not read-modify-write)
- **Thread in DM:** Allowed. Same behavior as channel threads.
- **Thread pagination:** Use `created_at ASC` (oldest first) — opposite of channel history which uses DESC.
- **Empty thread (0 replies):** Not a thread. Don't show thread indicator on parent.

### WS Events
- `thread.reply` (not `message.new`) — so clients distinguish thread replies from channel messages
- Fan out to: ALL channel members (so they see updated reply count), but only thread participants see the full reply content

---

## Reactions (Emoji)

### What It Is
Users add emoji reactions to messages. Each reaction shows the emoji + count + who reacted.

### Slack Behavior
- Any user can add multiple different emoji to the same message
- Same user CANNOT add the same emoji twice (toggle: add → remove → add)
- Reactions are shown below the message as emoji badges with counts
- Clicking an existing reaction toggles it (add if not reacted, remove if already reacted)
- Max reactions per message: typically 23 unique emoji (Slack limit). We use 50.
- Reactions persist (they're not ephemeral like typing indicators)

### Data Model
- `reactions` table: (id, tenant_id, channel_id, message_id, user_id, emoji, created_at)
- UNIQUE(message_id, user_id, emoji) — prevents duplicate
- Index on message_id for fetching all reactions on a message

### API Endpoints
- `POST /api/v1/channels/{channelId}/messages/{messageId}/reactions` — body: `{"emoji": "+1"}` — add reaction (or 409 if already exists)
- `DELETE /api/v1/channels/{channelId}/messages/{messageId}/reactions/{emoji}` — remove own reaction
- `GET /api/v1/channels/{channelId}/messages/{messageId}/reactions` — returns `[{emoji, count, userIds, reacted}]`
- Include reaction summary in MessageResponse when fetching history (aggregate: `{emoji: count}` map, plus `myReactions: [emoji]` for current user)

### Edge Cases
- **React to deleted message:** Block it. Check `is_deleted = false` before allowing.
- **React to message in channel you left:** Block it. Verify membership.
- **Concurrent add of same reaction:** UNIQUE constraint handles it — catch `DataIntegrityViolationException`, return 409.
- **Unicode emoji:** Store the emoji string as-is (`:thumbsup:` shortcode OR Unicode `👍`). Standardize to Unicode on server.
- **Custom emoji:** Future feature. For now, only standard Unicode emoji. Validate with a reasonable length check (max 100 chars).

### WS Events
- `reaction.added` — fan out to channel members (everyone sees reaction count update)
- `reaction.removed` — same

---

## Message Edit

### What It Is
Users can edit their own messages after sending. The edited message replaces the original in the channel, with an "(edited)" indicator.

### Slack Behavior
- Only the SENDER can edit their own message (not admins — different from delete)
- Edited messages show "(edited)" with timestamp
- No edit history visible to users (Slack doesn't show previous versions)
- Media messages: can edit the text caption, cannot change the media
- System messages: cannot be edited
- Edit window: no time limit (Slack allows editing any time)

### Data Model
- `messages.edited_at` — NULL if never edited, set to `Instant.now()` on edit
- Update `messages.content` in place (no version history table needed for MVP)
- Do NOT update `created_at` (preserve original timestamp)

### API Endpoints
- `PATCH /api/v1/channels/{channelId}/messages/{messageId}` — body: `{"content": "new text"}`
- Returns updated `MessageResponse` (with `editedAt` field)

### Edge Cases
- **Edit by non-sender:** 403 Forbidden. Only `message.sender_id == currentUserId`.
- **Edit deleted message:** Block it. Check `is_deleted = false`.
- **Edit with empty content:** If message has media, allow empty content (media-only). If no media, require non-empty content.
- **Edit system message:** Block it. Check `message_type != SYSTEM`.
- **Concurrent edits:** Last write wins (no conflict resolution needed for chat).
- **Idempotency on edit:** Not needed — edits are inherently idempotent (same content = same result).

### WS Events
- `message.edited` — fan out to channel members (everyone sees updated content + edited indicator)

---

## Message Delete

### What It Is
Users can delete their own messages. Admins can delete any message in channels they admin.

### Slack Behavior
- Sender can delete own message (any time)
- Workspace admin/channel admin can delete any message
- Deleted messages disappear from the channel (not shown as "[deleted]" in Slack's default view)
- In threads: if parent is deleted, thread remains accessible but parent shows as deleted
- System messages cannot be deleted

### Data Model
- `messages.is_deleted = true` (soft delete — already exists)
- Do NOT hard delete (preserve for audit, thread integrity)
- History queries already filter `is_deleted = false`

### API Endpoints
- `DELETE /api/v1/channels/{channelId}/messages/{messageId}`
- Returns 200 with empty success response

### Edge Cases
- **Delete by non-sender non-admin:** 403 Forbidden.
- **Delete already-deleted message:** Idempotent — return 200 (not 404).
- **Delete thread parent:** Set `is_deleted = true` but do NOT delete thread replies. Thread replies remain accessible.
- **Delete thread reply:** Decrement parent's `reply_count`. Use atomic SQL: `UPDATE messages SET reply_count = reply_count - 1 WHERE id = :parentId AND reply_count > 0`.
- **Delete message with reactions:** Soft-delete message. Reactions remain in DB (orphaned) — they'll be hidden since the message is filtered out.

### WS Events
- `message.deleted` — fan out to channel members

---

## Typing Indicators

### What It Is
Shows "User X is typing..." in real-time when someone is composing a message.

### Slack Behavior
- Shows up to 3 names: "Alice is typing...", "Alice and Bob are typing...", "Alice, Bob, and Carol are typing...", "Several people are typing..."
- Typing indicator auto-expires after 5 seconds of inactivity
- Typing is per-channel (not per-thread)
- Only shown to other channel members (not to the typer themselves)

### Data Model
- **No database.** Typing is purely ephemeral (Redis + WebSocket).
- Redis key: `typing:{channelId}` — SET of user IDs with TTL
- Or simpler: no Redis at all — just fan out the WS event. Client manages display.

### Implementation
- Client sends `{"type": "typing", "channelId": "..."}` via WebSocket
- WsHandler receives it, builds `typing.start` event, fans out to channel members (exclude sender)
- Client auto-hides typing indicator after 5 seconds of no new typing events from that user
- **No trackUnread** — typing is ephemeral, don't increment unread for offline users

### Edge Cases
- **Typing in channel you're not a member of:** Ignore silently (don't error — just don't fan out).
- **Rapid typing events:** Client should throttle to max 1 typing event per 3 seconds.
- **User disconnects while "typing":** Client-side timeout handles it (5 seconds).

### WS Events
- `typing.start` — fan out to channel members, no unread tracking

---

## User Presence (Online/Offline/Away)

### What It Is
Shows whether a user is currently online, away, or offline. Green dot = online, yellow dot = away, grey = offline.

### Slack Behavior
- **Online:** Active WebSocket connection + recent activity
- **Away:** Connected but inactive for 30 minutes (or manually set)
- **Offline:** No WebSocket connection
- **Do Not Disturb:** Manual status, suppresses notifications
- Presence is visible to all users in the same tenant

### Data Model
- **No database** for live presence (Redis only)
- Redis SET: `online:{tenantId}` — all online user IDs
- Redis Hash: `presence:{tenantId}:{userId}` — `{status: "online"|"away"|"dnd", lastSeen: timestamp}`
- User entity: `status_text`, `avatar_url` (profile, not live presence)

### Implementation
- On WS connect: `SADD online:{tenantId} {userId}`, publish `presence.change` (online)
- On WS disconnect: `SREM online:{tenantId} {userId}`, publish `presence.change` (offline)
- Away detection: optional — client sends heartbeat, if no heartbeat for 30 min → set away
- Fan out via `fanoutService.fanoutToTenant()` (all online users see it, not channel-scoped)

### Edge Cases
- **Multiple devices:** User can be connected from 2 devices. Only mark offline when ALL connections close. Use Redis `ws:conn:{tenantId}:{userId}` — check if any connection remains before publishing offline.
- **Rapid connect/disconnect:** Debounce — wait 5 seconds before publishing offline (prevents flicker when refreshing browser).
- **Presence for large tenants:** 10K users × frequent presence changes = high fan-out. Use `fanoutToTenant()` but with throttling (max 1 presence update per user per 10 seconds).

### WS Events
- `presence.change` — tenant-scoped (channelId = null), data: `{userId, status, lastSeen}`

---

## Pinned Messages

### What It Is
Important messages can be pinned to a channel. Pinned messages appear in a dedicated "Pins" panel.

### Slack Behavior
- Any channel member can pin/unpin a message
- Pinned messages are per-channel (not per-thread)
- Maximum 100 pinned messages per channel (Slack limit)
- A system message appears in the channel: "User pinned a message"
- Pinned messages list shows the message content + who pinned + when

### Data Model
- `pinned_messages` table (already exists): id, tenant_id, channel_id, message_id, pinned_by, created_at
- UNIQUE(channel_id, message_id) — can't pin same message twice

### API Endpoints
- `POST /api/v1/channels/{channelId}/pins/{messageId}` — pin
- `DELETE /api/v1/channels/{channelId}/pins/{messageId}` — unpin
- `GET /api/v1/channels/{channelId}/pins` — list pinned messages (include full message content)

### Edge Cases
- **Pin deleted message:** Block it. Validate message exists and `is_deleted = false`.
- **Pin message from another channel:** Block it. Validate `message.channel_id == channelId`.
- **Pin limit:** Check count before pinning. If >= 100, return error "Channel pin limit reached".
- **Concurrent pin:** UNIQUE constraint handles it — catch exception, return 409.
- **Unpin already-unpinned:** Idempotent — return 200.

### WS Events
- `pin.added` / `pin.removed` — fan out to channel members

---

## Channel Topic / Description

### What It Is
Channels have a topic (short, shown in header) and description (longer, shown in channel details).

### Slack Behavior
- Any member can set/change the topic (not just admins)
- Topic appears in the channel header bar
- Description appears in the "About" panel
- Changing topic creates a system message: "User set the channel topic: ..."
- Topic max length: 250 chars. Description max length: no hard limit (use 2000).

### Data Model
- `channels.topic` (TEXT, already exists), `channels.description` (TEXT, already exists)

### API Endpoints
- `PATCH /api/v1/channels/{channelId}` — body: `{"topic": "...", "description": "..."}`
- Either field can be updated independently (partial update)

### Edge Cases
- **Update archived channel:** Block it. Check `is_archived = false`.
- **Update DM topic:** Allowed in Slack. Allow it.
- **Empty topic/description:** Allowed (clears the field).
- **System message on change:** Create a system message with `message_type = SYSTEM` when topic changes.

### WS Events
- `channel.updated` — fan out to channel members, data includes changed fields

---

## Message Search

### What It Is
Full-text search across messages in channels the user is a member of.

### Slack Behavior
- Search is scoped to the user's accessible channels only
- Search results show message content, channel name, sender, timestamp
- Can filter by: channel, sender, date range
- Highlights matching terms in results

### Implementation (MVP)
- PostgreSQL `ILIKE` for simple search (sufficient for < 1M messages per tenant)
- Query: `SELECT * FROM messages WHERE tenant_id = :tid AND channel_id IN (:userChannelIds) AND content ILIKE '%query%' AND is_deleted = false ORDER BY created_at DESC LIMIT :limit`
- For scale: add PostgreSQL `tsvector` full-text search index, or Elasticsearch

### API Endpoints
- `GET /api/v1/search?q=text&channelId=optional&limit=20&before=optional`
- Returns `List<MessageResponse>` with channel context

### Edge Cases
- **Search in channels user is not a member of:** Filter results to member channels only. Fetch user's channel IDs first.
- **Empty query:** Return 400.
- **SQL injection:** Use parameterized queries (JPA does this automatically). Never concatenate user input into SQL.
- **Very long query:** Limit to 200 chars.
- **No results:** Return empty list, not 404.

---

## Scheduled Messages

### What It Is
Compose a message now, schedule it to be sent at a future time.

### Slack Behavior
- User writes message, picks a date/time, message is queued
- Scheduled messages can be viewed, edited, or deleted before send time
- At scheduled time, message is sent as if user typed it (appears in channel normally)
- If user is deactivated before send time, message is cancelled

### Data Model
- New table: `scheduled_messages` (id, tenant_id, channel_id, user_id, content, media_url, media_type, scheduled_at, status, created_at)
- Status: `PENDING`, `SENT`, `CANCELLED`, `FAILED`
- Index on `(status, scheduled_at)` for the polling query

### Implementation
- `@Scheduled(fixedDelay = 30000)` method polls for `WHERE status = 'PENDING' AND scheduled_at <= NOW()`
- For each: call `MessageService.sendMessage()` internally (reuse existing flow — persistence + fan-out)
- Update status to `SENT` on success, `FAILED` on error
- Use `SELECT ... FOR UPDATE SKIP LOCKED` to prevent multiple instances processing the same message

### Edge Cases
- **Schedule in the past:** Return 400 "Scheduled time must be in the future".
- **Schedule too far ahead:** Limit to 120 days.
- **Edit after sent:** 400 "Message already sent".
- **Channel deleted before send:** Set status to `FAILED`.
- **User removed from channel before send:** Check membership at send time. If not member, set `FAILED`.

---

## Starred Items / Bookmarks

### What It Is
Users can star messages, channels, or files for personal quick access.

### Slack Behavior
- Stars are personal (only the user sees their stars)
- Can star: messages, channels, files
- Starred items appear in a dedicated "Starred Items" sidebar section
- Starring is a toggle (star/unstar)

### Data Model
- `starred_items` table (already exists): id, tenant_id, user_id, item_type, item_id, channel_id, created_at
- UNIQUE(user_id, item_type, item_id)

### API Endpoints
- `POST /api/v1/stars` — body: `{"itemType": "message", "itemId": "uuid", "channelId": "uuid"}`
- `DELETE /api/v1/stars/{itemType}/{itemId}` — unstar
- `GET /api/v1/stars` — list all stars for current user

### Edge Cases
- **Star deleted message:** Allow it (user might want to track it). But show "[deleted]" in UI.
- **Star message in channel you left:** Allow it (bookmark is personal).
- **No WS event** — stars are personal, no need to notify others.

---

## Read Receipts

### What It Is
Track which messages each user has read. Shows unread count badges.

### Already Implemented
- `channel_members.last_read_msg_id` and `last_read_at` — tracks per user per channel
- Redis unread counts: `unread:{tenantId}:{userId}` hash
- `POST /api/v1/channels/{channelId}/read` with `lastReadMessageId`
- `GET /api/v1/unread` returns all unread counts

### Enhancement: Visible Read Receipts
- WS event `read.receipt` — shows other users when someone reads a message
- Data: `{userId, channelId, lastReadMessageId}`
- Only fan out in small channels (< 50 members) or DMs. In large channels, skip (too chatty).

---

## Notification Preferences

### What It Is
Per-channel mute and notification level settings.

### Data Model
- `channel_members.muted` (BOOLEAN, already exists)
- `channel_members.notification_level` (VARCHAR, already exists): "default", "all", "mentions", "none"

### API Endpoints
- `PATCH /api/v1/channels/{channelId}/notifications` — body: `{"muted": true, "level": "mentions"}`
- Returns updated preference

### Behavior
- `muted = true` → no notifications for this channel (but unread count still increments)
- `level = "all"` → notify on every message
- `level = "mentions"` → notify only when @mentioned
- `level = "none"` → no notifications ever
- `level = "default"` → follow tenant-level default

### Fan-out Impact
- When fanning out: check recipient's notification preference before sending push (future FCM/APNs)
- WS delivery is NOT affected by mute (messages still appear in real-time, just no notification badge/sound)

---

## User Profiles

### What It Is
Extended user information: avatar, status text, timezone.

### Data Model (all exist)
- `users.avatar_url` — URL to profile image (uploaded via media-service)
- `users.status_text` — custom status ("In a meeting", "On vacation")
- `users.timezone` — IANA timezone ("America/New_York")

### API Endpoints
- `PATCH /api/v1/users/profile` — body: `{"statusText": "...", "timezone": "...", "avatarUrl": "..."}`
- `GET /api/v1/users/{userId}/profile` — public profile info (name, avatar, status, timezone)
- `GET /api/v1/users` — list all users in tenant (for @mention autocomplete)

### Edge Cases
- **Invalid timezone:** Validate against known IANA timezones.
- **Avatar upload:** Client uploads via media-service first (gets URL), then calls profile update.
- **Status with emoji:** Allow it. Status text is plain text (emoji are Unicode chars).

---

## Mentions (@user, @channel, @here)

### What It Is
@ mentions notify specific users or groups when they're mentioned in a message.

### Types
- `@user` — notifies specific user
- `@channel` — notifies ALL channel members
- `@here` — notifies only ONLINE channel members

### Implementation
- Parse message content for `@` patterns on the SERVER (not client)
- Store mentions in a mentions table or as a JSON array on the message
- For MVP: parse `@userId` patterns (UUID-based), not display names
- Client sends: `{"content": "Hey <@userId123> check this"}` — server extracts mentions

### Data Model (MVP — no separate table)
- Parse mentions from `message.content` at send time
- Create notification entries (future notifications table)
- For MVP: just fan out with higher priority (e.g., push notification flag in WS event)

### Edge Cases
- **Mention user not in channel:** Include in WS event data but don't send them the message (they're not a member)
- **Mention in thread:** Only notify if user is thread participant or explicitly @mentioned
- **@channel in large channel:** Rate limit — don't allow @channel in channels with > 1000 members unless sender is admin
