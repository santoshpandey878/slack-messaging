# WebSocket & Real-Time Events

## Connection

```
ws://localhost:8080/ws?token=<JWT>
```

Client connects via API Gateway (:8080) which transparently proxies to ws-gateway (:8085).

## Event Envelope

ALL WebSocket events follow this format:

```json
{
  "type": "event.type",
  "tenantId": "uuid",
  "channelId": "uuid or null",
  "timestamp": "2026-06-11T10:30:00Z",
  "data": {
    // event-specific fields
  }
}
```

- `channelId` is null for tenant-scoped events (presence)
- `data` contains event-specific payload

## Event Types (WsEventType enum)

| Type | Category | Scoped To | Description |
|------|----------|-----------|-------------|
| `message.new` | Message | Channel | New message sent |
| `message.edited` | Message | Channel | Message content edited |
| `message.deleted` | Message | Channel | Message soft-deleted |
| `thread.reply` | Thread | Channel | Reply in a thread |
| `reaction.added` | Reaction | Channel | Emoji reaction added |
| `reaction.removed` | Reaction | Channel | Emoji reaction removed |
| `typing.start` | Typing | Channel | User started typing |
| `typing.stop` | Typing | Channel | User stopped typing |
| `presence.change` | Presence | Tenant | User online/offline/away |
| `channel.updated` | Channel | Channel | Name/topic/description changed |
| `channel.archived` | Channel | Channel | Channel archived |
| `member.joined` | Member | Channel | User joined channel |
| `member.left` | Member | Channel | User left channel |
| `pin.added` | Pin | Channel | Message pinned |
| `pin.removed` | Pin | Channel | Message unpinned |
| `read.receipt` | Read | Channel | User read up to a message |

## Client → Server Messages

| Type | Payload | Response |
|------|---------|----------|
| `ping` | `{"type":"ping"}` | `{"type":"pong"}` |
| `sync` | `{"type":"sync","channels":{"chId":"lastMsgId",...}}` | Missed messages + `{"type":"sync_complete"}` |

## Delivery Modes

### Channel-Scoped (channelId is set)
Only channel members receive the event. WsHandler checks membership before delivery.

### Tenant-Scoped (channelId is null)
ALL online users in the tenant receive the event (e.g., presence changes).

## Message Delivery Pipeline

```
Service (e.g., MessageService)
    │
    ▼
WsPayloadBuilder.buildXxx()        ← builds JSON envelope
    │
    ▼
FanoutService.fanoutEvent()         ← looks up online members in Redis
    │
    ├─► Online: pubSub.publish("ws:server:{serverId}", payload)
    │       │
    │       ▼
    │   WsHandler receives via Pub/Sub subscription
    │       │
    │       ▼
    │   deliverToChannelMembers() or deliverToTenantMembers()
    │       │
    │       ▼
    │   WebSocketSession.sendMessage(payload)
    │
    └─► Offline: cache.hincrBy(unread:{tenantId}:{userId}, channelId, 1)
```

## How to Add a New Event Type

### 1. Add enum value
**File:** `common/src/main/java/com/slackmsg/domain/enums/WsEventType.java`
```java
MY_EVENT("my.event"),
```

### 2. Add payload builder
**File:** `common/src/main/java/com/slackmsg/util/WsPayloadBuilder.java`
```java
public static String buildMyEvent(UUID tenantId, UUID channelId,
                                   String someField, ObjectMapper objectMapper) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("someField", someField);
    return buildEvent(WsEventType.MY_EVENT, tenantId, channelId, data, objectMapper);
}
```

### 3. Fan out from your service
```java
@Service
public class MyService {
    private final FanoutService fanoutService;
    private final ObjectMapper objectMapper;

    public void doSomething(UUID channelId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // ... business logic ...

        // Fan out to channel members (exclude current user)
        String payload = WsPayloadBuilder.buildMyEvent(tenantId, channelId, "value", objectMapper);
        fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false);
    }
}
```

### 4. That's it
WsHandler already handles generic event delivery. No changes needed in ws-gateway.

## Reconnection Sync

When client reconnects, it sends:
```json
{"type":"sync","channels":{"channel-id-1":"last-msg-id","channel-id-2":"last-msg-id"}}
```

Server responds with all messages after the given IDs (up to 100 per channel), then sends `{"type":"sync_complete"}`.

Currently syncs messages only. Future: extend to sync reactions, pins, etc.

## Redis Keys for WebSocket

| Key Pattern | Type | Purpose |
|-------------|------|---------|
| `ws:conn:{tenantId}:{userId}` | Hash | Which server a user is connected to |
| `ws:server:{serverId}` | Pub/Sub channel | Server-specific event delivery |
| `online:{tenantId}` | Set | All online user IDs in tenant |
| `typing:{channelId}` | Set | Users currently typing |
| `unread:{tenantId}:{userId}` | Hash | Unread counts per channel |

## Multi-Server Scaling

Each ws-gateway instance has a unique `serverId`. Fan-out looks up which server each user is on and publishes only to that server's Pub/Sub channel. No broadcast to all servers.
