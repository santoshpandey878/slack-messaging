# Slack-like Messaging System

A multi-tenant, real-time messaging system built with hexagonal architecture (ports & adapters). Designed to ship fast as an MVP and evolve to scale **without rewriting business logic**.

## Quick Start

```bash
# 1. Start infrastructure (PostgreSQL + Redis + MinIO)
docker-compose up -d

# 2. Start application (requires Java 11)
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn spring-boot:run

# 3. Open in browser
#    Demo UI:     http://localhost:8080
#    Swagger:     http://localhost:8080/swagger-ui.html
#    Actuator:    http://localhost:8080/actuator/health
#    MinIO UI:    http://localhost:9001 (minioadmin/minioadmin)
```

### Full Reset (clean all data and restart fresh)

```bash
docker-compose down -v && docker-compose up -d && sleep 5 && \
  JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn spring-boot:run
```

## Features

| Feature | Status |
|---------|--------|
| Multi-tenant isolation (JWT + scoped queries + MDC logging) | ✅ |
| Create channels (public, private) | ✅ |
| 1-1 direct messages (idempotent, deduplicated, race-safe) | ✅ |
| Group messaging | ✅ |
| Send text messages | ✅ |
| Send media-only messages | ✅ |
| Media upload (presigned S3/MinIO, content-type whitelist) | ✅ |
| Real-time delivery (WebSocket + Redis Pub/Sub, channel-filtered) | ✅ |
| Message history (cursor-based pagination) | ✅ |
| Unread counts (per-user, per-channel) | ✅ |
| Mark as read | ✅ |
| Reconnection sync (fetch missed messages) | ✅ |
| Idempotency (per-user scoped, duplicate prevention) | ✅ |
| Rate limiting (per-tenant + per-user, Redis sliding window) | ✅ |
| Login protection (5 attempts → 15 min lockout) | ✅ |
| Add/remove channel members (paginated, race-safe) | ✅ |
| Role-based access (tenant admin, channel admin, member) | ✅ |
| Password hashing (BCrypt 12 rounds) | ✅ |
| Input validation (filename sanitization, size limits) | ✅ |
| Spring Actuator (health, metrics endpoints) | ✅ |
| Structured logging (MDC with tenantId, userId) | ✅ |
| HTML demo client (real-time chat, media upload, activity log) | ✅ |
| 68 automated tests (58 unit + 10 integration) + E2E script | ✅ |

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 11 (Amazon Corretto) |
| Framework | Spring Boot 2.7.18 |
| Database | PostgreSQL 14 |
| Cache & Pub/Sub | Redis 7 |
| Object Storage | MinIO (S3-compatible) |
| WebSocket | Spring WebSocket (raw handler) |
| Auth | JWT (jjwt) |
| Migration | Flyway |
| API Docs | springdoc-openapi (Swagger UI) |
| Build | Maven |

## Architecture

### Hexagonal (Ports & Adapters)

```
Handlers (REST + WebSocket)
    │
    ▼
Services (Business Logic) ── never changes for infra reasons
    │
    ▼
Ports (Interfaces) ── the architectural contracts
    │
    ▼
Adapters (Implementations) ── THIS is what we swap at scale
    ├── adapter/postgres/  → PostgreSQL (MVP)
    ├── adapter/redis/     → Redis cache + Pub/Sub
    ├── adapter/s3/        → MinIO / S3
    ├── adapter/cassandra/ → (future: swap MessageStore)
    ├── adapter/kafka/     → (future: swap PubSubService)
    └── adapter/grpc/      → (future: microservice split)
```

### Separation of Concerns

```
CROSS-CUTTING (separated from business logic):
  JwtAuthFilter          → Authentication (JWT + TenantContext + MDC)
  RateLimitFilter        → Rate limiting (per-tenant + per-user)
  GlobalExceptionHandler → Error handling (sanitized responses)
  AuthorizationService   → Authorization (admin checks — centralized)
  LoginGuardService      → Login attempt tracking + lockout
  PasswordService        → Password encoding/verification (BCrypt)
  IdempotencyService     → Dedup key management

BUSINESS (SRP — each service does ONE thing, <100 lines):
  AuthService          → Register + Login (delegates password, guard)
  ChannelService       → Channel CRUD + ChannelServicePort
  DmService            → DM create/dedup/name enrichment
  MembershipService    → Add/remove/list members (batch optimized)
  MessageService       → Validate + persist (delegates fanout, idempotency)
  FanoutService        → Online push + offline unread (server-deduped)
  UnreadService        → Get/reset unread counts
  MediaService         → Presigned URL + validation
  WsSessionManager     → WebSocket session state + connection keys (DRY)

HANDLERS (thin orchestrators — delegate to services):
  ChannelHandler → ChannelService + DmService + MembershipService
  MessageHandler → MessageService + UnreadService
  UserHandler    → AuthService + AuthorizationService
  WsHandler      → WsSessionManager + MessageServicePort + PubSub
```

### Key Design Decisions

**1. Interface-driven storage** — `MessageStore` is a pure interface. MVP uses `PostgresMessageStore`. At scale, swap to `CassandraMessageStore` with zero business logic changes.

**2. Cross-module via service ports** — `MessageService` calls `ChannelServicePort.isMember()`, not `ChannelStore` directly. When splitting into microservices, only the port implementation changes (local call → gRPC).

**3. Config-driven evolution** — same binary, different config:
```
MVP:   MESSAGE_STORE=postgres  BROKER=redis    FANOUT=sync
Scale: MESSAGE_STORE=cassandra BROKER=kafka    FANOUT=async
```

## API Reference

**Swagger UI:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
**OpenAPI JSON:** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### Auth (no token required)

```bash
# Register a new tenant + admin user
POST /api/v1/auth/register
{
  "tenantName": "Porter",
  "tenantSlug": "porter",
  "email": "admin@porter.com",
  "displayName": "Admin",
  "password": "secret123"
}

# Login
POST /api/v1/auth/login
{
  "tenantSlug": "porter",
  "email": "admin@porter.com",
  "password": "secret123"
}
# Returns: { token, userId, tenantId, displayName, role }
```

### Users (admin only)

```bash
# Add user to tenant
POST /api/v1/users
Authorization: Bearer {token}
{ "email": "bob@porter.com", "displayName": "Bob", "password": "secret123" }
```

### Channels

```bash
# Create channel
POST /api/v1/channels
Authorization: Bearer {token}
{ "name": "general", "type": "PUBLIC" }

# List my channels
GET /api/v1/channels

# Get channel details
GET /api/v1/channels/{channelId}

# Create or get DM (idempotent — same pair always returns same channel)
POST /api/v1/dm
{ "userId": "{targetUserId}" }

# Add members (batch)
POST /api/v1/channels/{channelId}/members
{ "userIds": ["{userId1}", "{userId2}"] }

# Remove member
DELETE /api/v1/channels/{channelId}/members/{userId}

# List members
GET /api/v1/channels/{channelId}/members
```

### Messages

```bash
# Send message
POST /api/v1/channels/{channelId}/messages
Authorization: Bearer {token}
{
  "content": "Hello team!",
  "idempotencyKey": "client-generated-uuid"   # optional, prevents duplicates
}

# Send message with media
POST /api/v1/channels/{channelId}/messages
{
  "content": "Check this screenshot",
  "mediaUrl": "https://...",
  "mediaType": "image/png"
}

# Get message history (cursor-based pagination)
GET /api/v1/channels/{channelId}/messages?limit=50
GET /api/v1/channels/{channelId}/messages?before=2026-06-03T00:00:00Z&limit=50

# Mark channel as read
POST /api/v1/channels/{channelId}/read
{ "lastReadMessageId": "{messageId}" }

# Get all unread counts
GET /api/v1/unread
# Returns: { "channelId1": "3", "channelId2": "7" }
```

### Media

```bash
# Get presigned upload URL (client uploads directly to S3/MinIO)
POST /api/v1/media/upload-url
Authorization: Bearer {token}
{
  "fileName": "screenshot.png",
  "contentType": "image/png",
  "sizeBytes": 1024000
}
# Returns: { uploadUrl, readUrl, mediaId, key }
```

### WebSocket

```javascript
// Connect
const ws = new WebSocket('ws://localhost:8080/ws?token={jwt}');

// Receive real-time messages
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  // msg.type = "message.new"
  // msg.channelId = "..."
  // msg.message = { id, senderId, content, messageType, createdAt }
};

// Ping/pong (keepalive)
ws.send(JSON.stringify({ type: "ping" }));

// Reconnect sync (fetch missed messages)
ws.send(JSON.stringify({
  type: "sync",
  channels: {
    "channel-id-1": "last-seen-message-id",
    "channel-id-2": "last-seen-message-id"
  }
}));
```

## Testing

### Test Summary

| Test Suite | Tests | What It Covers |
|------------|-------|---------------|
| AuthServiceTest | 6 | Register, duplicate slug, login, wrong password, account lockout, deactivated user |
| ChannelServiceTest | 14 | Channel CRUD (create, limit, DM rejected), DM dedup (new, existing, self, target-not-found), membership (add, DM rejected, already-member batch skip), access control, service port |
| MessageServiceTest | 8 | Send, not-a-member, idempotency cached, media type, empty content, fan-out failure resilience, history, limit clip |
| FanoutTest | 6 | Sender skipped, online push, offline unread, mixed routing, server dedup, payload structure |
| WsHandlerTest | 11 | Connect valid/invalid/no-token, disconnect cleanup, ping/pong, unknown type, invalid JSON, sync missed, Pub/Sub member delivery, non-member blocked, cross-tenant blocked |
| RateLimitFilterTest | 4 | Under limit, tenant exceeded (429), user exceeded (429), no context bypass |
| MediaServiceTest | 4 | Upload URL, file too large, invalid content type, path traversal |
| **MessagingIntegrationTest** | **10** | Full E2E: register, duplicate, login, add user, create channel, add member, send text, send media, empty rejected, cross-tenant isolation, no-auth, health, actuator |
| **TOTAL** | **63** | |

### Run Unit Tests

```bash
# Run all unit tests (58 tests, ~4 seconds)
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test

# Run a specific test class
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test -Dtest=AuthServiceTest
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test -Dtest=WsHandlerTest
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test -Dtest=FanoutTest
```

### Run Integration Tests (requires Docker)

Integration tests run against real PostgreSQL + Redis. They are skipped by default and enabled via environment variable.

```bash
# 1. Start Docker infra
docker-compose up -d

# 2. Wait for containers to be healthy
docker ps   # verify all 3 containers are "Up" and "healthy"

# 3. Reset database (clean state)
psql postgresql://slackuser:slackpass@localhost:5432/slackmsg \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO slackuser;"
redis-cli FLUSHALL

# 4. Run integration tests
RUN_INTEGRATION_TESTS=true \
  JAVA_HOME=$(/usr/libexec/java_home -v 11) \
  mvn test -Dtest=com.slackmsg.integration.MessagingIntegrationTest
```

### Run All Tests Together

```bash
# Unit tests (always)
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test

# Integration tests (requires Docker running)
RUN_INTEGRATION_TESTS=true \
  JAVA_HOME=$(/usr/libexec/java_home -v 11) \
  mvn test -Dtest=com.slackmsg.integration.MessagingIntegrationTest
```

### Run E2E Test Script (automated curl — 21 checks)

The `test.sh` script runs 21 automated checks against the running app covering: register, login, wrong password, add user, create channel, add member, list members, send message, idempotency, history, unread, mark-as-read, DM dedup, media upload, invalid content type, cross-tenant isolation, no auth, rate limiting.

```bash
# 1. Start infra + app
docker-compose up -d
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn spring-boot:run &

# 2. Wait for app to start, then run test script
sleep 10
chmod +x test.sh
./test.sh

# Expected output:
# ═══════════════════════════════════════════
#  Slack Messaging — E2E Test
# ═══════════════════════════════════════════
#   ✅ GET /health
#   ✅ GET /actuator/health
#   ✅ POST /auth/register
#   ✅ POST /auth/login
#   ... (21 checks)
# ═══════════════════════════════════════════
#  Results: 21 passed, 0 failed
# ═══════════════════════════════════════════
```

### Manual Testing with curl

Start the application first:
```bash
docker-compose up -d
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn spring-boot:run
```

**Complete end-to-end test script:**

```bash
BASE=http://localhost:8080

# ──────────────────────────────────────────
# 1. REGISTER TENANT + ADMIN USER
# ──────────────────────────────────────────
echo "=== Register ==="
REG=$(curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"tenantName":"Porter","tenantSlug":"porter","email":"santosh@porter.com","displayName":"Santosh","password":"test123456"}')
echo $REG | python3 -m json.tool
TOKEN=$(echo $REG | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
USER1_ID=$(echo $REG | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['userId'])")

# ──────────────────────────────────────────
# 2. LOGIN
# ──────────────────────────────────────────
echo "=== Login ==="
curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"porter","email":"santosh@porter.com","password":"test123456"}' | python3 -m json.tool

# ──────────────────────────────────────────
# 3. ADD SECOND USER
# ──────────────────────────────────────────
echo "=== Add User ==="
USER2=$(curl -s -X POST $BASE/api/v1/users \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"email":"bob@porter.com","displayName":"Bob","password":"test123456"}')
echo $USER2 | python3 -m json.tool
USER2_ID=$(echo $USER2 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['userId'])")

# Login as Bob
TOKEN2=$(curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"porter","email":"bob@porter.com","password":"test123456"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# ──────────────────────────────────────────
# 4. CREATE CHANNEL
# ──────────────────────────────────────────
echo "=== Create Channel ==="
CH=$(curl -s -X POST $BASE/api/v1/channels \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"general","type":"PUBLIC"}')
echo $CH | python3 -m json.tool
CH_ID=$(echo $CH | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

# ──────────────────────────────────────────
# 5. ADD BOB TO CHANNEL
# ──────────────────────────────────────────
echo "=== Add Member ==="
curl -s -X POST "$BASE/api/v1/channels/$CH_ID/members" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"userIds\":[\"$USER2_ID\"]}" | python3 -m json.tool

# ──────────────────────────────────────────
# 6. LIST MEMBERS
# ──────────────────────────────────────────
echo "=== List Members ==="
curl -s "$BASE/api/v1/channels/$CH_ID/members" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# ──────────────────────────────────────────
# 7. SEND MESSAGES
# ──────────────────────────────────────────
echo "=== Send Message 1 (Santosh) ==="
curl -s -X POST "$BASE/api/v1/channels/$CH_ID/messages" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"content":"Hello team!","idempotencyKey":"msg-001"}' | python3 -m json.tool

echo "=== Send Message 2 (Bob) ==="
curl -s -X POST "$BASE/api/v1/channels/$CH_ID/messages" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN2" \
  -d '{"content":"Hey Santosh!"}' | python3 -m json.tool

# ──────────────────────────────────────────
# 8. IDEMPOTENCY TEST (same key → same message)
# ──────────────────────────────────────────
echo "=== Idempotency (duplicate → returns cached) ==="
curl -s -X POST "$BASE/api/v1/channels/$CH_ID/messages" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"content":"Hello team!","idempotencyKey":"msg-001"}' | python3 -m json.tool

# ──────────────────────────────────────────
# 9. GET MESSAGE HISTORY
# ──────────────────────────────────────────
echo "=== Message History ==="
curl -s "$BASE/api/v1/channels/$CH_ID/messages?limit=10" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# ──────────────────────────────────────────
# 10. UNREAD COUNTS (Bob has unread from Santosh)
# ──────────────────────────────────────────
echo "=== Unread Counts (Bob) ==="
curl -s "$BASE/api/v1/unread" \
  -H "Authorization: Bearer $TOKEN2" | python3 -m json.tool

# ──────────────────────────────────────────
# 11. MARK AS READ
# ──────────────────────────────────────────
MSG_ID=$(curl -s "$BASE/api/v1/channels/$CH_ID/messages" \
  -H "Authorization: Bearer $TOKEN2" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")

echo "=== Mark as Read ==="
curl -s -X POST "$BASE/api/v1/channels/$CH_ID/read" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN2" \
  -d "{\"lastReadMessageId\":\"$MSG_ID\"}" | python3 -m json.tool

echo "=== Unread After Read (Bob — should be 0) ==="
curl -s "$BASE/api/v1/unread" \
  -H "Authorization: Bearer $TOKEN2" | python3 -m json.tool

# ──────────────────────────────────────────
# 12. CREATE DM (idempotent)
# ──────────────────────────────────────────
echo "=== Create DM ==="
curl -s -X POST "$BASE/api/v1/dm" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"userId\":\"$USER2_ID\"}" | python3 -m json.tool

echo "=== Create DM Again (should return same channel) ==="
curl -s -X POST "$BASE/api/v1/dm" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"userId\":\"$USER2_ID\"}" | python3 -m json.tool

# ──────────────────────────────────────────
# 13. MEDIA UPLOAD URL
# ──────────────────────────────────────────
echo "=== Get Upload URL ==="
curl -s -X POST "$BASE/api/v1/media/upload-url" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"fileName":"photo.png","contentType":"image/png","sizeBytes":1024000}' | python3 -m json.tool

echo "=== Invalid Content Type (should fail) ==="
curl -s -X POST "$BASE/api/v1/media/upload-url" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"fileName":"virus.exe","contentType":"application/x-executable","sizeBytes":1024}' | python3 -m json.tool

# ──────────────────────────────────────────
# 14. RATE LIMITING
# ──────────────────────────────────────────
echo "=== Rate Limit Test (15 rapid requests, limit=10/sec) ==="
for i in $(seq 1 15); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/channels" \
    -H "Authorization: Bearer $TOKEN")
  echo "Request $i: HTTP $CODE"
done

# ──────────────────────────────────────────
# 15. CROSS-TENANT ISOLATION
# ──────────────────────────────────────────
echo "=== Register Second Tenant ==="
REG2=$(curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"tenantName":"Acme","tenantSlug":"acme","email":"alice@acme.com","displayName":"Alice","password":"test123456"}')
TOKEN3=$(echo $REG2 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

echo "=== Cross-tenant: Acme tries to access Porter channel (should fail) ==="
curl -s -X POST "$BASE/api/v1/channels/$CH_ID/messages" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN3" \
  -d '{"content":"Trying to snoop!"}' | python3 -m json.tool

# ──────────────────────────────────────────
# 16. NO AUTH → 401
# ──────────────────────────────────────────
echo "=== No Auth (should return 401) ==="
curl -s "$BASE/api/v1/channels" | python3 -m json.tool

# ──────────────────────────────────────────
# 17. HEALTH + ACTUATOR
# ──────────────────────────────────────────
echo "=== Health ==="
curl -s "$BASE/health" | python3 -m json.tool

echo "=== Actuator Health ==="
curl -s "$BASE/actuator/health" | python3 -m json.tool

echo "=== Swagger UI ==="
echo "Open: http://localhost:8080/swagger-ui.html"
```

### Testing WebSocket (Browser)

1. Start the application: `mvn spring-boot:run`
2. Open **http://localhost:8080** in two browser tabs
3. **Tab 1**: Register as `santosh@porter.com`, create channel "general"
4. **Tab 2**: Login as `bob@porter.com` (add Bob first via Tab 1)
5. Copy the channel ID from Tab 1, paste into Tab 2
6. Send messages from both tabs — see real-time delivery
7. Check the **WebSocket Log** panel for connection events and message payloads

### Testing WebSocket with wscat

```bash
# Install wscat
npm install -g wscat

# Get a token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"porter","email":"santosh@porter.com","password":"test123456"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# Connect via WebSocket
wscat -c "ws://localhost:8080/ws?token=$TOKEN"

# Once connected, you'll see:
# < {"type":"connected","serverId":"ws-server-xxxxx"}

# Send ping:
> {"type":"ping"}
# Response: {"type":"pong"}

# Sync missed messages (after reconnect):
> {"type":"sync","channels":{"CHANNEL_ID":"LAST_MSG_ID"}}
# Response: missed messages + {"type":"sync_complete"}
```

### Expected Test Results

```
Unit Tests:     53 passed, 0 failed (runs without Docker)
Integration:    10 passed, 0 failed (requires Docker)
────────────────────────────────────────────────────────
Total:          63 passed, 0 failed

Manual curl:    17 scenarios, all pass (test.sh)
WebSocket:      Connect, ping/pong, real-time delivery, sync — all work
Rate limiting:  First 10 requests → 200, requests 11-15 → 429
Cross-tenant:   Acme cannot access Porter channels → 403
No auth:        → 401
```

## Project Structure

```
src/main/java/com/slackmsg/
├── domain/entity/          # Tenant, User, Channel, ChannelMember, Message, DmPair
├── domain/enums/           # ChannelType, MemberRole, MessageType
├── port/repository/        # Data ports: TenantStore, UserStore, ChannelStore, MessageStore
├── port/service/           # Service ports: ChannelServicePort, MessageServicePort, PubSub, Cache, Storage
├── adapter/postgres/repo/  # PostgreSQL implementations (swappable to Cassandra)
├── adapter/redis/          # Redis cache + Pub/Sub (swappable to Kafka)
├── adapter/s3/             # MinIO/S3 storage
├── service/                # Business logic (SRP — each class does ONE thing):
│   ├── AuthService         #   Register + Login
│   ├── ChannelService      #   Channel CRUD + ChannelServicePort
│   ├── DmService           #   DM create/dedup/name enrichment
│   ├── MembershipService   #   Add/remove/list members
│   ├── MessageService      #   Validate + persist
│   ├── FanoutService       #   Online push + offline unread
│   ├── IdempotencyService  #   Dedup key management
│   ├── UnreadService       #   Unread counts
│   ├── MediaService        #   Presigned URL + validation
│   ├── WsSessionManager    #   WebSocket session state
│   ├── PasswordService     #   BCrypt (cross-cutting)
│   ├── LoginGuardService   #   Login lockout (cross-cutting)
│   └── AuthorizationService#   Admin checks (cross-cutting)
├── handler/                # Thin REST + WebSocket orchestrators
├── handler/dto/            # Request/response DTOs with validation
├── handler/middleware/     # JWT auth + MDC, rate limit, exception handler
├── config/                 # Spring configuration
└── util/                   # ApiResponse, JwtUtil, TenantContext, WsPayloadBuilder

src/test/java/com/slackmsg/
├── service/                # Unit tests: Auth(6), Channel(14), Message(8), Fanout(6),
│                           #   Media(4), RateLimit(4)
├── handler/                # WsHandler tests (11)
└── integration/            # E2E integration tests (10, requires Docker)

test.sh                     # Automated E2E curl script (21 checks)
```

## Message Flow

```
SEND MESSAGE (REST):
  Client → JwtAuthFilter (validate) → RateLimitFilter (check)
    → MessageHandler → MessageService
      → ChannelServicePort.isMember() (validate membership)
      → CacheService.get() (idempotency check)
      → MessageStore.save() (persist to PostgreSQL)
      → CacheService.set() (cache idempotency key)
      → Fan-out:
          → For online users: PubSubService.publish() → Redis → WS server → Client
          → For offline users: CacheService.hincrBy() (increment unread count)
      → Return ACK (~25ms)

RECEIVE MESSAGE (WebSocket):
  Redis Pub/Sub → WsHandler → find local socket → push to client

RECONNECT:
  Client sends sync request → WsHandler → MessageServicePort.getMessagesAfter()
    → push missed messages → send sync_complete
```

## Multi-Tenancy

Every request is tenant-scoped:

1. **JWT** — `tenant_id`, `userId`, `role`, `displayName` embedded in token claims
2. **TenantContext** — thread-local, set per request by `JwtAuthFilter`
3. **MDC Logging** — `tenantId` and `userId` in every log line via SLF4J MDC
4. **Scoped queries** — every database query includes `WHERE tenant_id = ?`
5. **Rate limits** — per-tenant (100 req/sec) + per-user (10 req/sec)
6. **Login protection** — 5 failed attempts → 15 minute lockout (per email)
7. **Isolation** — tenant A cannot see tenant B's channels, messages, or users

## Evolution Path (No Rewrite)

| Trigger | Action | Effort |
|---------|--------|--------|
| Fan-out latency > 50ms | Write `KafkaBroker` adapter (implements `PubSubService`) | 2 days |
| Message table > 500GB | Write `CassandraMessageStore` (implements `MessageStore`) | 3 days |
| Connections > 50K | Extract `WsHandler` into separate service | 2 days |
| Team > 8 engineers | Split modules into microservices (gRPC adapters for service ports) | 1 week |

Business logic changes needed: **zero**.

## Infrastructure

### Docker Compose (local development)

| Service | Port | Purpose |
|---------|------|---------|
| PostgreSQL 14 | 5432 | All data (tenants, users, channels, messages) |
| Redis 7 | 6379 | Pub/Sub, cache, unread counts, rate limits, connection registry |
| MinIO | 9000 (API), 9001 (Console) | S3-compatible object storage for media |

### Database Schema (Flyway managed — V1 + V2)

- `tenants` — multi-tenant root
- `users` — tenant-scoped, BCrypt password hash
- `channels` — public, private, DM types
- `channel_members` — membership with roles, CASCADE delete
- `messages` — with idempotency key, indexed by (tenant, channel, time, sender)
- `dm_pairs` — sorted UUID pair for DM deduplication, CASCADE delete

## Configuration

All config in `src/main/resources/application.yml`. Secrets are externalized to environment variables:

| Config | Env Var | Default | Description |
|--------|---------|---------|-------------|
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/slackmsg` | Database URL |
| `spring.datasource.username` | `DB_USERNAME` | `slackuser` | DB user |
| `spring.datasource.password` | `DB_PASSWORD` | `slackpass` | DB password |
| `spring.redis.host` | `REDIS_HOST` | `localhost` | Redis host |
| `jwt.secret` | `JWT_SECRET` | (dev default) | JWT signing key (**set in prod!**) |
| `jwt.expiration-ms` | `JWT_EXPIRY_MS` | 900000 (15 min) | Token expiry |
| `storage.endpoint` | `STORAGE_ENDPOINT` | `http://localhost:9000` | MinIO/S3 endpoint |
| `storage.access-key` | `STORAGE_ACCESS_KEY` | `minioadmin` | S3 access key |
| `storage.secret-key` | `STORAGE_SECRET_KEY` | `minioadmin` | S3 secret key |
| `storage.bucket` | `STORAGE_BUCKET` | `slack-media` | Media bucket |
| `app.max-channels-per-tenant` | — | 1000 | Channel limit per tenant |
| `app.rate-limit.tenant-per-second` | — | 100 | Tenant rate limit |
| `app.rate-limit.user-per-second` | — | 10 | User rate limit |
| `app.ws.allowed-origins` | `WS_ALLOWED_ORIGINS` | `http://localhost:8080` | WebSocket CORS |
| `logging.level.com.slackmsg` | `LOG_LEVEL` | `DEBUG` | App log level |
