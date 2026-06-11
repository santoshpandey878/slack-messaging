# Error Handling

Complete guide to exception handling, graceful degradation, and failure recovery patterns.

---

## Exception Hierarchy

```
GlobalExceptionHandler (catches all)
├── IllegalArgumentException → 400 Bad Request
│   ├── "Channel not found"
│   ├── "User already exists"
│   ├── "Message must have content or media"
│   ├── "Too many login attempts"
│   └── Any validation/business rule failure
├── SecurityException → 403 Forbidden
│   ├── "Not a member of this channel"
│   ├── "Admin access required"
│   └── Any authorization failure
├── MethodArgumentNotValidException → 400 Bad Request
│   └── Bean validation failures (@NotBlank, @Size, etc.)
├── HttpMessageNotReadableException → 400 Bad Request
│   └── JSON parse errors, invalid UUID format
├── DataAccessException → 500 Internal Server Error
│   └── Database errors (logged with stack trace, generic message to client)
└── Exception (catch-all) → 500 Internal Server Error
    └── Unexpected errors (logged with stack trace, generic message to client)
```

## When to Throw What

```java
// Validation error (client's fault — bad input)
throw new IllegalArgumentException("Channel name is required");

// Authorization error (client lacks permission)
throw new SecurityException("Not a member of this channel");

// Resource not found (treated as validation error — 400, not 404)
throw new IllegalArgumentException("Channel not found");

// Duplicate resource (catch DB constraint, throw business exception)
catch (DataIntegrityViolationException e) {
    throw new IllegalArgumentException("Reaction already exists");
}

// Or for idempotent operations — don't throw, return existing
catch (DataIntegrityViolationException e) {
    log.debug("Already exists, returning existing: {}", e.getMessage());
    return existingEntity;
}
```

## Fan-Out Error Handling (Critical Pattern)

Fan-out MUST NOT fail the main operation. This is the single most important error handling pattern.

```java
// In MessageService.sendMessage():
Message message = messageStore.save(message);  // ← this MUST succeed
idempotencyService.markCompleted(...);          // ← this can fail (Redis), acceptable

try {
    fanoutService.fanout(tenantId, channelId, message, senderId, senderName);
} catch (Exception e) {
    // SWALLOW — fan-out failure is acceptable
    log.error("Fan-out failed for msgId={}: {}. Recipients will catch up via sync.",
            message.getId(), e.getMessage());
}

return MessageResponse.from(message);  // ← always return success
```

## Inter-Service Error Handling

### Security-Critical Calls (message-service → channel-service)
```java
// ChannelServiceClient in message-service: THROWS on failure
@Override
public boolean isMember(UUID channelId, UUID userId) {
    try {
        Map result = get("/internal/channels/" + channelId + "/is-member/" + userId, Map.class);
        return Boolean.TRUE.equals(result.get("member"));
    } catch (Exception e) {
        log.error("Channel service unavailable: {}", e.getMessage());
        throw new SecurityException("Unable to verify channel membership");  // FAIL CLOSED
    }
}
```

### Best-Effort Calls (ws-gateway → channel-service)
```java
// ChannelServiceClient in ws-gateway: returns false on failure
@Override
public boolean isMember(UUID channelId, UUID userId) {
    try {
        Map result = get("/internal/channels/" + channelId + "/is-member/" + userId, Map.class);
        return Boolean.TRUE.equals(result.get("member"));
    } catch (Exception e) {
        log.debug("Membership check failed, skipping delivery: {}", e.getMessage());
        return false;  // FAIL OPEN for delivery (message already persisted)
    }
}
```

## Graceful Degradation Matrix

| Component Down | What Degrades | What Still Works |
|---------------|---------------|-----------------|
| Redis | Fan-out, unread counts, rate limiting, idempotency | Message persistence, history, channel CRUD |
| channel-service | Message send (can't verify membership) | Auth, media, existing WS connections |
| message-service | WS reconnect sync | Auth, channels, media, new WS connections |
| MinIO | Media uploads | Everything else |
| Redis Pub/Sub | Real-time delivery | Message persistence, REST API, history |

## Error Response Format

All errors use the same `ApiResponse` wrapper:

```json
{
    "success": false,
    "message": "Human-readable error description"
}
```

- **Never include stack traces** in client responses
- **Never include SQL error details** in client responses
- **Always log the real error** server-side before returning generic message
- **Include entity IDs** in server logs for debugging
