# API Design

## Conventions

- **Base path:** `/api/v1/`
- **Auth:** `Authorization: Bearer <JWT>` header on all requests (except register/login)
- **Content-Type:** `application/json`
- **Response wrapper:** All responses use `ApiResponse<T>`:
```json
{
  "success": true,
  "message": "optional message",
  "data": { ... }
}
```
- **Error response:**
```json
{
  "success": false,
  "message": "Error description"
}
```
- **IDs:** UUID format everywhere
- **Timestamps:** ISO-8601 Instant (e.g., `2026-06-11T10:30:00Z`)
- **Pagination:** Cursor-based for messages (`?before=<Instant>&limit=50`)

## Current Endpoints

### Auth Service (:8081)

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| POST | `/api/v1/auth/register` | RegisterRequest | AuthResponse | Creates tenant + admin |
| POST | `/api/v1/auth/login` | LoginRequest | AuthResponse | Returns JWT |
| POST | `/api/v1/users` | AddUserRequest | User map | Admin-only |

### Channel Service (:8082)

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| POST | `/api/v1/channels` | CreateChannelRequest | ChannelResponse | |
| GET | `/api/v1/channels` | — | List\<ChannelResponse\> | User's channels |
| GET | `/api/v1/channels/{id}` | — | ChannelResponse | |
| POST | `/api/v1/dm` | CreateDmRequest | ChannelResponse | Deduplicates |
| POST | `/api/v1/channels/{id}/members` | AddMembersRequest | {added, requested} | Admin-only |
| DELETE | `/api/v1/channels/{id}/members/{userId}` | — | — | Self or admin |
| GET | `/api/v1/channels/{id}/members` | ?limit&offset | List\<UUID\> | |

**Internal (no auth):**
| Method | Path | Response |
|--------|------|----------|
| GET | `/internal/channels/{id}/is-member/{userId}` | {member: bool} |
| GET | `/internal/channels/{id}/member-ids` | List\<UUID\> |

### Message Service (:8083)

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| POST | `/api/v1/channels/{id}/messages` | SendMessageRequest | MessageResponse | Idempotent |
| GET | `/api/v1/channels/{id}/messages` | ?before&limit | List\<MessageResponse\> | Cursor pagination |
| POST | `/api/v1/channels/{id}/read` | MarkReadRequest | — | Mark read |
| GET | `/api/v1/unread` | — | Map\<channelId, count\> | |

**Internal (no auth):**
| Method | Path | Response |
|--------|------|----------|
| GET | `/internal/messages/after/{channelId}/{afterMsgId}` | List\<MessageResponse\> |

### Media Service (:8084)

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| POST | `/api/v1/media/upload-url` | UploadUrlRequest | {uploadUrl, readUrl, key} | Presigned |

### API Gateway (:8080)

Routes all `/api/*` to backend services. Serves static HTML at `/`.

## Adding a New Endpoint

1. Add handler method in the owning service
2. Follow path conventions: `/api/v1/{resource}` or `/api/v1/channels/{id}/{sub-resource}`
3. Always return `ApiResponse<T>`
4. Always use `@Valid` on request bodies
5. Check if API Gateway routing needs updating (usually not — most channel sub-resources already route correctly)

## Request DTOs

**Location:** `common/src/main/java/com/slackmsg/dto/request/`

```java
@Data
public class MyRequest {
    @NotBlank private String name;        // required string
    @NotNull private UUID targetId;       // required UUID
    private String optional;              // optional (no annotation)
    @Size(max = 40960) private String content;  // size-limited
    @Email private String email;          // email format
    @Positive private long sizeBytes;     // positive number
}
```

## Response DTOs

**Location:** `common/src/main/java/com/slackmsg/dto/response/`

Always include:
- `@JsonInclude(JsonInclude.Include.NON_NULL)` — omit null fields
- Static `from(Entity)` factory method
- `@Data @Builder`

## API Gateway Routing

**File:** `api-gateway/src/main/java/com/slackmsg/gateway/config/ServiceRoutes.java`

Routes are matched by path prefix:
- `/api/v1/auth/*`, `/api/v1/users` → auth-service
- `/api/v1/channels/*`, `/api/v1/dm` → channel-service (except messages/read/unread)
- `/api/v1/channels/*/messages*`, `/api/v1/channels/*/read`, `/api/v1/unread` → message-service
- `/api/v1/media/*` → media-service
- `/ws` → ws-gateway

To add a new route for a completely new path prefix, update `ServiceRoutes.resolveUrl()`.
