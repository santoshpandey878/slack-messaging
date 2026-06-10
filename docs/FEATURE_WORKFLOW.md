# Feature Workflow — How to Add Any Feature

This is the step-by-step cookbook for adding any Slack feature. Follow these steps in order. No architecture decisions needed — just follow the pattern.

## Quick Reference: Which Service Owns What

| Feature Area | Service | Handler File |
|-------------|---------|-------------|
| Auth, users, profiles | auth-service | `AuthHandler.java`, `UserHandler.java` |
| Channels, DMs, membership | channel-service | `ChannelHandler.java` |
| Messages, threads, reactions, pins, search | message-service | `MessageHandler.java` |
| File uploads | media-service | `MediaHandler.java` |
| Real-time events | ws-gateway | `WsHandler.java` |
| Routing | api-gateway | `ServiceRoutes.java` |

## Step-by-Step: Adding a New Feature

### Step 1: Database (if persistent data needed)

**File:** `{service}/src/main/resources/db/migration/V{N}__{description}.sql`

Create a new Flyway migration. Copy to ALL 5 services (auth, channel, message, media, ws-gateway).

```sql
-- Example: Adding a "bookmarks" feature
CREATE TABLE IF NOT EXISTS bookmarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(channel_id, url)
);
CREATE INDEX IF NOT EXISTS idx_bookmarks_channel ON bookmarks(channel_id);

-- Or adding a column to existing table:
ALTER TABLE messages ADD COLUMN IF NOT EXISTS some_field VARCHAR(255);
```

**Rules:**
- Always use `IF NOT EXISTS` / `IF NOT EXISTS` (idempotent)
- Always include `tenant_id` column for multi-tenancy
- Always add relevant indexes
- Copy migration to ALL 5 service dirs (shared DB, first service to start runs it)
- Increment version number from last migration (check existing V*.sql files)

### Step 2: Entity (if new table or new columns)

**File:** `common/src/main/java/com/slackmsg/domain/entity/{EntityName}.java`

```java
package com.slackmsg.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;
import javax.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookmarks", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"channel_id", "url"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Bookmark {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

**For existing entities** — just add the field with `@Column`:
```java
@Column(name = "edited_at")
private Instant editedAt;
```

### Step 3: Repository (JPA)

**File:** `{service}/src/main/java/com/slackmsg/{service}/adapter/postgres/{Entity}Repository.java`

```java
package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Bookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {
    List<Bookmark> findByChannelIdOrderByCreatedAtDesc(UUID channelId);
    void deleteByIdAndChannelId(UUID id, UUID channelId);
}
```

### Step 4: Request/Response DTOs

**Request:** `common/src/main/java/com/slackmsg/dto/request/{FeatureName}Request.java`

```java
package com.slackmsg.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.UUID;

@Data
public class AddBookmarkRequest {
    @NotBlank private String name;
    @NotBlank private String url;
}
```

**Response:** `common/src/main/java/com/slackmsg/dto/response/{FeatureName}Response.java`

```java
package com.slackmsg.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slackmsg.domain.entity.Bookmark;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookmarkResponse {
    private UUID id;
    private String name;
    private String url;
    private UUID addedBy;
    private Instant createdAt;

    public static BookmarkResponse from(Bookmark b) {
        return BookmarkResponse.builder()
                .id(b.getId())
                .name(b.getName())
                .url(b.getUrl())
                .addedBy(b.getUserId())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
```

### Step 5: Service (business logic)

**File:** `{service}/src/main/java/com/slackmsg/{service}/service/{FeatureName}Service.java`

```java
package com.slackmsg.message.service;

import com.slackmsg.domain.entity.Bookmark;
import com.slackmsg.dto.request.AddBookmarkRequest;
import com.slackmsg.message.adapter.postgres.BookmarkRepository;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookmarkService {

    private final BookmarkRepository bookmarkRepo;

    public Bookmark addBookmark(UUID channelId, AddBookmarkRequest request) {
        Bookmark bookmark = Bookmark.builder()
                .tenantId(TenantContext.getTenantId())
                .userId(TenantContext.getUserId())
                .channelId(channelId)
                .name(request.getName())
                .url(request.getUrl())
                .build();
        return bookmarkRepo.save(bookmark);
    }

    public List<Bookmark> listBookmarks(UUID channelId) {
        return bookmarkRepo.findByChannelIdOrderByCreatedAtDesc(channelId);
    }

    public void removeBookmark(UUID channelId, UUID bookmarkId) {
        bookmarkRepo.deleteByIdAndChannelId(bookmarkId, channelId);
    }
}
```

**Rules:**
- Always use `TenantContext.getTenantId()` and `TenantContext.getUserId()` — never pass from handler
- Keep under 100 lines. Split into multiple services if larger.
- Use `@RequiredArgsConstructor` + `private final` for dependency injection
- Never catch exceptions in services (let `GlobalExceptionHandler` handle them)

### Step 6: Handler (REST endpoint)

**File:** `{service}/src/main/java/com/slackmsg/{service}/handler/{FeatureName}Handler.java`

```java
package com.slackmsg.message.handler;

import com.slackmsg.dto.request.AddBookmarkRequest;
import com.slackmsg.dto.response.BookmarkResponse;
import com.slackmsg.message.service.BookmarkService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/channels/{channelId}/bookmarks")
@RequiredArgsConstructor
public class BookmarkHandler {

    private final BookmarkService bookmarkService;

    @PostMapping
    public ResponseEntity<ApiResponse<BookmarkResponse>> add(
            @PathVariable UUID channelId,
            @Valid @RequestBody AddBookmarkRequest request) {
        var bookmark = bookmarkService.addBookmark(channelId, request);
        return ResponseEntity.ok(ApiResponse.ok("Bookmark added", BookmarkResponse.from(bookmark)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookmarkResponse>>> list(@PathVariable UUID channelId) {
        var bookmarks = bookmarkService.listBookmarks(channelId).stream()
                .map(BookmarkResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(bookmarks));
    }

    @DeleteMapping("/{bookmarkId}")
    public ResponseEntity<ApiResponse<Void>> remove(
            @PathVariable UUID channelId, @PathVariable UUID bookmarkId) {
        bookmarkService.removeBookmark(channelId, bookmarkId);
        return ResponseEntity.ok(ApiResponse.ok("Bookmark removed", null));
    }
}
```

**Rules:**
- Handlers are THIN — no business logic, just extract params + call service + return ApiResponse
- Always use `@Valid` on request bodies
- Always return `ApiResponse<T>`
- Path pattern: `/api/v1/{resource}` or `/api/v1/channels/{channelId}/{sub-resource}`

### Step 7: API Gateway Route (if new path pattern)

**File:** `api-gateway/src/main/java/com/slackmsg/gateway/config/ServiceRoutes.java`

Add route if the path doesn't already match an existing pattern. Most `/api/v1/channels/{id}/*` paths already route to the correct service.

Check existing routes first — you may not need to change anything.

### Step 8: Real-Time Event (if feature needs WebSocket notification)

**8a. Build the event payload:**

Use existing builder in `WsPayloadBuilder.java`, or add a new one:

```java
// In WsPayloadBuilder.java
public static String buildBookmarkAdded(UUID tenantId, UUID channelId,
                                         String name, String url, ObjectMapper objectMapper) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("name", name);
    data.put("url", url);
    return buildEvent(WsEventType.CHANNEL_UPDATED, tenantId, channelId, data, objectMapper);
}
```

**8b. Add event type (if needed):**

Add to `WsEventType.java` enum if no existing type fits:
```java
BOOKMARK_ADDED("bookmark.added"),
```

**8c. Fan out the event:**

In your service, inject `FanoutService` and call:
```java
// Channel-scoped event (only channel members receive it)
String payload = WsPayloadBuilder.buildBookmarkAdded(tenantId, channelId, name, url, objectMapper);
fanoutService.fanoutEvent(tenantId, channelId, payload, currentUserId, false);

// Tenant-scoped event (all online users in tenant receive it)
String payload = WsPayloadBuilder.buildPresenceChange(tenantId, userId, "online", objectMapper);
fanoutService.fanoutToTenant(tenantId, payload, null);
```

**Parameters:**
- `excludeUserId`: user to skip (usually the actor), null to include all
- `trackUnread`: true for messages (increment offline unread count), false for ephemeral events

### Step 9: Internal API (if cross-service data needed)

**File:** `{service}/src/main/java/com/slackmsg/{service}/handler/{Service}InternalHandler.java`

```java
@RestController
@RequestMapping("/internal/bookmarks")
@RequiredArgsConstructor
public class BookmarkInternalHandler {
    // Internal endpoints are NOT behind JWT auth (excluded in JwtAuthFilter)
    // Used by other services for cross-service queries
}
```

**Client side:** Create `client/{Service}Client.java` extending `ServiceClient` in the calling service.

### Step 10: Test

Run the full E2E test:
```bash
./test-e2e.sh
```

Add new test cases to `test-e2e.sh` for the new feature. Follow the existing curl pattern.

### Step 11: Build & Deploy

```bash
# Compile
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -N -q && mvn install -pl common -q && mvn package -DskipTests -q

# Deploy to Docker
docker-compose build --quiet && docker-compose up -d

# Verify
./test-e2e.sh
```

---

## Common Feature Patterns

### Pattern A: Add Reactions to Messages

1. **Entity:** `Reaction.java` (already exists in common)
2. **Repository:** `ReactionRepository.java` in message-service
3. **DTOs:** `AddReactionRequest.java`, `ReactionResponse.java`
4. **Service:** `ReactionService.java` in message-service
5. **Handler:** Add to `MessageHandler.java` or new `ReactionHandler.java`:
   - `POST /api/v1/channels/{channelId}/messages/{messageId}/reactions`
   - `DELETE /api/v1/channels/{channelId}/messages/{messageId}/reactions/{emoji}`
   - `GET /api/v1/channels/{channelId}/messages/{messageId}/reactions`
6. **WS Event:** `WsPayloadBuilder.buildReactionAdded()` (already exists)
7. **Fanout:** `fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false)`

### Pattern B: Add Threads/Replies

1. **Entity:** `Message.parentMessageId` (column already exists)
2. **Service:** Update `MessageService.sendMessage()` to accept `parentMessageId`
3. **Handler:** Update `MessageHandler`:
   - POST body already has `SendMessageRequest` — add `parentMessageId` field
   - Add `GET /api/v1/channels/{channelId}/threads/{messageId}` for thread replies
4. **WS Event:** `WsPayloadBuilder.buildThreadReply()` (already exists)
5. **Fanout:** Same as message fan-out, just different event type

### Pattern C: Add Message Edit

1. **Entity:** `Message.editedAt` (column already exists)
2. **DTOs:** `EditMessageRequest.java` (content field only)
3. **Service:** `MessageService.editMessage(channelId, messageId, newContent)`
   - Validate ownership (senderId == currentUserId)
   - Update content + editedAt
4. **Handler:** `PATCH /api/v1/channels/{channelId}/messages/{messageId}`
5. **WS Event:** `WsPayloadBuilder.buildMessageEdited()` (already exists)
6. **Fanout:** `fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false)`

### Pattern D: Add Message Delete

1. **Entity:** `Message.isDeleted` (already exists, soft delete)
2. **Service:** `MessageService.deleteMessage(channelId, messageId)`
   - Validate ownership or admin
   - Set `isDeleted = true`
3. **Handler:** `DELETE /api/v1/channels/{channelId}/messages/{messageId}`
4. **WS Event:** `WsPayloadBuilder.buildMessageDeleted()` (already exists)

### Pattern E: Add Typing Indicators

1. **No database** — purely real-time
2. **WS Handler:** Add `case "typing":` in `WsHandler.handleTextMessage()`:
   - Extract channelId from client message
   - Build `WsPayloadBuilder.buildTypingStart()` (already exists)
   - Call `fanoutService.fanoutEvent()` — no unread tracking
3. **Client sends:** `{"type": "typing", "channelId": "..."}`
4. **Redis TTL key** (optional): `typing:{channelId}:{userId}` with 5s TTL

### Pattern F: Add User Presence

1. **WS Gateway:** In `WsSessionManager.register()` — publish presence.online event
2. **WS Gateway:** In `WsSessionManager.unregister()` — publish presence.offline event
3. **Redis:** `SADD online:{tenantId} {userId}` on connect, `SREM` on disconnect
4. **WS Event:** `WsPayloadBuilder.buildPresenceChange()` (already exists)
5. **Fanout:** `fanoutService.fanoutToTenant()` (already exists, broadcasts to all)

### Pattern G: Add Pinned Messages

1. **Entity:** `PinnedMessage.java` (already exists in common)
2. **Repository:** `PinnedMessageRepository.java` in message-service
3. **Handler:**
   - `POST /api/v1/channels/{channelId}/pins/{messageId}`
   - `DELETE /api/v1/channels/{channelId}/pins/{messageId}`
   - `GET /api/v1/channels/{channelId}/pins`
4. **WS Event:** `WsPayloadBuilder.buildPinAdded()` (already exists)

### Pattern H: Add Channel Topic/Description

1. **Entity:** `Channel.topic`, `Channel.description` (columns already exist)
2. **DTOs:** `UpdateChannelRequest.java` (topic, description)
3. **Service:** `ChannelService.updateChannel(channelId, request)`
4. **Handler:** `PATCH /api/v1/channels/{channelId}`
5. **WS Event:** `WsPayloadBuilder.buildChannelUpdated()` (already exists)

### Pattern I: Add Message Search

1. **Repository:** Add `MessageRepository.searchByContent(tenantId, query, limit)`
   - Use `@Query("SELECT m FROM Message m WHERE m.tenantId = :tid AND LOWER(m.content) LIKE LOWER(CONCAT('%', :q, '%'))")`
2. **Service:** `SearchService.search(query, channelId, limit)`
3. **Handler:** `GET /api/v1/search?q=text&channelId=X&limit=20`
4. **No WS event** (search is query-only)

### Pattern J: Add Scheduled Messages

1. **Entity:** New `ScheduledMessage.java` (id, tenantId, channelId, userId, content, scheduledAt, status)
2. **Migration:** New table `scheduled_messages`
3. **Service:** `ScheduledMessageService` with `@Scheduled` method that polls every 30s
4. **Handler:**
   - `POST /api/v1/channels/{channelId}/scheduled-messages`
   - `GET /api/v1/scheduled-messages`
   - `DELETE /api/v1/scheduled-messages/{id}`
5. **Execution:** When `scheduledAt <= now()`, call `MessageService.sendMessage()` internally
