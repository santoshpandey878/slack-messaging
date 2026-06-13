# Slack Messaging System

A production-grade, multi-tenant, real-time messaging platform built with hexagonal architecture across six independently deployable microservices.

---

## Architecture

```
                          +------------------+
                          |   API Gateway    |
                          |    (8080)        |
                          +--------+---------+
                                   |
           +-----------+-----------+-----------+-----------+
           |           |           |           |           |
     +-----+----+ +----+-----+ +--+-------+ +-+--------+ +----+------+
     |   Auth   | | Channel  | | Message  | |  Media   | |    WS     |
     | Service  | | Service  | | Service  | | Service  | |  Gateway  |
     |  (8081)  | |  (8082)  | |  (8083)  | |  (8084)  | |  (8085)   |
     +-----+----+ +----+-----+ +--+-------+ +-+--------+ +----+------+
           |           |           |           |                |
     +-----+-----------+-----------+-----------+----------------+
     |                      INFRASTRUCTURE                      |
     |  PostgreSQL 14    Redis 7    MinIO (S3)                  |
     |   (5432)          (6379)     (9000/9001)                 |
     +----------------------------------------------------------+
```

## Tech Stack

| Component        | Technology                      |
|------------------|---------------------------------|
| Language         | Java 11 (Amazon Corretto)       |
| Framework        | Spring Boot 2.7.18              |
| Database         | PostgreSQL 14                   |
| Cache / Pub-Sub  | Redis 7                         |
| Object Storage   | MinIO (S3-compatible)           |
| WebSocket        | Spring WebSocket (raw handler)  |
| Authentication   | JWT (jjwt 0.11.5)              |
| Migrations       | Flyway                          |
| Build            | Maven (multi-module)            |
| Containerization | Docker + Docker Compose         |
| Browser Testing  | Playwright (Dockerized)         |

## Current Features

| Feature | Status | Service |
|---------|--------|---------|
| Multi-tenant registration & login | Live | auth-service |
| JWT authentication & token management | Live | auth-service |
| Login lockout (5 failed attempts) | Live | auth-service |
| Public/Private channels | Live | channel-service |
| Direct messages (DM) with dedup | Live | channel-service |
| Channel membership (add/remove/list) | Live | channel-service |
| Real-time messaging via WebSocket | Live | message-service + ws-gateway |
| Message history (cursor pagination) | Live | message-service |
| Message idempotency (duplicate prevention) | Live | message-service |
| Threads / Replies | Live | message-service |
| Emoji Reactions (add/remove/toggle) | Live | message-service |
| Pinned Messages (pin/unpin/list) | Live | message-service |
| Message Search (per-channel) | Live | message-service |
| Typing Indicators ("X is typing...") | Live | ws-gateway |
| User Presence (online/offline) | Live | ws-gateway |
| Browser Notifications | Live | frontend (Notification API) |
| Unread counts with badges | Live | message-service + frontend |
| Media upload via presigned URLs | Live | media-service |
| Cross-tenant isolation | Live | all services |
| Rate limiting (per-tenant, per-user) | Live | all services |

## How to Test: Current Features

### 1. Registration & Login
1. Open http://localhost:8080
2. Enter workspace slug "demo", email "admin@demo.com", name "Admin", password "test123456"
3. Click **Register New Workspace** — you should see "Logged in as Admin" and WebSocket status turns green
4. Click **Login / Register** again, enter same slug/email/password, click **Login** — should login successfully

### 2. Channels & Messaging
1. Click **+ Create Channel**, name it "general", click **Create**
2. Type a message in the composer, press Enter or click **Send**
3. Message appears immediately in the chat area

### 3. Multi-User Real-Time (WebSocket)
1. Open http://localhost:8080 in a second browser tab (incognito)
2. Login with the same workspace slug but a different user (invite via **+ Invite User** first)
3. Both users select the same channel
4. User A sends a message — it appears in User B's tab in real-time via WebSocket
5. User B sends a message — it appears in User A's tab in real-time

### 4. Direct Messages
1. Copy User B's ID (shown under their name in sidebar)
2. In User A's tab, click **+ Direct Message**, paste the ID, click **Start DM**
3. Send messages back and forth — they appear in real-time

### 5. Media Upload
1. Click the paperclip icon in the composer
2. Select an image file — it uploads via presigned URL and appears in the chat

### 6. Threads (Replies)
1. Send a message in a channel
2. Click **Reply** on any message — thread panel opens on the right
3. Type a reply and click **Reply** — it appears in the thread panel
4. The parent message shows "1 reply" indicator
5. In a second tab (User B), the reply count updates in real-time

### 7. Reactions (Emoji)
1. Click **React** on any message — emoji picker appears
2. Click an emoji — reaction badge appears below the message with count
3. Click the badge again to remove your reaction
4. In a second tab (User B), add a different emoji — both users see both reactions

### 8. Pinned Messages
1. Click **Pin** on any message — toast shows "Message pinned"
2. Click **Pins** in the top bar — shows list of pinned messages
3. Click **Unpin** to remove a pin

### 9. Message Search
1. Click **Search** in the top bar — search bar appears
2. Type a keyword and press Enter — matching messages from the current channel are shown
3. Click **Close** to dismiss search results and return to normal history

### 10. Typing Indicators
1. Open two browser tabs with different users in the same channel
2. Start typing in User A's tab — User B sees "Admin is typing..."
3. The indicator auto-hides after 5 seconds of inactivity

### 11. User Presence
1. When a user connects via WebSocket, their status broadcasts as "online"
2. When they disconnect (close tab), status broadcasts as "offline"
3. Presence events are delivered to all users in the same tenant

### 12. Browser Notifications
1. Allow notifications when prompted (or click the browser permissions)
2. Minimize the browser tab
3. Another user sends a message — a desktop notification pops up with the sender name and message content

### 13. Cross-Tenant Isolation
1. Register a second workspace with a different slug
2. Try to access the first workspace's channels — should be blocked

---

## What Makes This Project Complex

This is not a simple CRUD app. Here's the engineering depth:

### 1. Hexagonal Architecture (Ports & Adapters)
Every service follows strict layered architecture: Handler → Service → Port (interface) → Adapter. Business logic depends ONLY on interfaces. You can swap PostgreSQL for Cassandra, Redis for Memcached, or MinIO for S3 by implementing a new adapter — zero business logic changes. This is the same pattern used at Netflix and Spotify.

### 2. Multi-Tenant Data Isolation
Every database query, every Redis key, every WebSocket delivery is scoped to a tenant. JWT carries `tenantId`, `JwtAuthFilter` sets `TenantContext` (ThreadLocal), and all data access enforces tenant boundaries. A bug in one tenant cannot leak data to another.

### 3. Real-Time Fan-Out Pipeline
Messages flow through a 10-step pipeline: API Gateway → message-service validates membership (REST to channel-service) → persists to PostgreSQL → FanoutService checks Redis for each member's connection → online members get Redis Pub/Sub push → ws-gateway delivers to WebSocket sessions (membership-verified) → offline members get unread count incremented. All best-effort — fan-out failure never fails the message send.

### 4. Idempotency & Race Condition Handling
- Message send is idempotent via Redis-backed idempotency keys (5-min TTL)
- Concurrent reactions handled via UNIQUE constraints + DataIntegrityViolationException catch
- Reply counts use atomic SQL (`SET reply_count = reply_count + 1`), never read-modify-write
- DM creation deduplicated via sorted user ID pairs

### 5. Three-Layer Test Strategy
- **Unit tests** (Java/JUnit/Mockito) — test business logic in isolation
- **E2E tests** (curl-based `test-e2e.sh`) — test REST API across all 5 services
- **Browser tests** (Playwright in Docker `test-browser.sh`) — test real multi-user WebSocket interactions in Chromium. These catch bugs that curl tests fundamentally cannot: WS sender echo double-counting, cross-user delivery, DOM rendering issues

### 6. Full CI/CD Pipeline
- **CI**: GitHub Actions — builds, runs unit tests + E2E on every push
- **CD**: Local watcher (`scripts/cd-watcher.sh`) polls GitHub every 30s, auto-deploys on new commits, runs health checks + E2E, logs results
- Pipeline is mandatory: Code → Unit Tests → Docker Deploy → Health Check → E2E → Browser Tests → Commit → Push → CI → CD auto-deploy → Verify

### 7. Production-Grade Knowledge Base
16 documentation files covering: domain knowledge for 17 Slack features, pre-decided architectural tradeoffs, code quality standards, edge cases (30+ patterns), error handling hierarchy, feature workflow (20-step cookbook), frontend guide, NFR guide, and full reference docs. An AI agent can build features autonomously using only this knowledge base.

### 8. Extensibility by Design
V4 database migration pre-creates columns and tables for future features (threads, reactions, pins, stars, user profiles, channel topics) without changing existing behavior. WsEventType enum has 16 event types pre-defined. WsPayloadBuilder has all event builders ready. A new feature plugs into existing infrastructure.

---

## Quick Start

### Prerequisites
- Java 11 (Amazon Corretto recommended)
- Maven 3.8+
- Docker and Docker Compose

### Build & Deploy

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)

# Build all modules
mvn install -N -q && mvn install -pl common -q && mvn package -DskipTests -q

# Deploy (full reset)
docker-compose down -v && docker-compose build --quiet && docker-compose up -d

# Verify all 6 services are UP
for PORT in 8080 8081 8082 8083 8084 8085; do
  curl -s "http://localhost:$PORT/actuator/health" | python3 -c "import sys,json; print(':$PORT →', json.load(sys.stdin).get('status','DOWN'))"
done
```

### Run Tests

```bash
# Unit tests
mvn test

# E2E tests (requires Docker containers running)
./test-e2e.sh

# Browser tests — multi-user Playwright tests via Docker (no Node.js needed)
./test-browser.sh
```

### Demo UI

Open **http://localhost:8080** — built-in Slack-like chat client.

---

## Service Ports

| Service          | Port | Responsibility                          |
|------------------|------|-----------------------------------------|
| API Gateway      | 8080 | Reverse proxy, routing, static assets   |
| Auth Service     | 8081 | Registration, login, JWT, user mgmt     |
| Channel Service  | 8082 | Channels, DMs, membership               |
| Message Service  | 8083 | Messages, threads, reactions, unread    |
| Media Service    | 8084 | Presigned upload/download URLs          |
| WS Gateway       | 8085 | WebSocket connections, real-time events  |

## API Endpoints

### Auth Service (no token required)

| Method | Endpoint                  | Description                    |
|--------|---------------------------|--------------------------------|
| POST   | `/api/v1/auth/register`   | Register tenant + admin user   |
| POST   | `/api/v1/auth/login`      | Login, returns JWT             |
| POST   | `/api/v1/users`           | Add user to tenant (admin)     |

### Channel Service

| Method | Endpoint                                   | Description               |
|--------|--------------------------------------------|---------------------------|
| POST   | `/api/v1/channels`                         | Create channel            |
| GET    | `/api/v1/channels`                         | List my channels          |
| GET    | `/api/v1/channels/{id}`                    | Get channel details       |
| POST   | `/api/v1/dm`                               | Create/get DM (idempotent)|
| POST   | `/api/v1/channels/{id}/members`            | Add members (batch)       |
| DELETE | `/api/v1/channels/{id}/members/{userId}`   | Remove member             |
| GET    | `/api/v1/channels/{id}/members`            | List members              |

### Message Service

| Method | Endpoint                                   | Description                  |
|--------|--------------------------------------------|------------------------------|
| POST   | `/api/v1/channels/{id}/messages`           | Send message                 |
| GET    | `/api/v1/channels/{id}/messages`           | History (cursor pagination)  |
| POST   | `/api/v1/channels/{id}/read`               | Mark as read                 |
| GET    | `/api/v1/unread`                           | Get unread counts            |

### Media Service

| Method | Endpoint                      | Description                  |
|--------|-------------------------------|------------------------------|
| POST   | `/api/v1/media/upload-url`    | Get presigned upload URL     |

### WebSocket Gateway

Connect via `ws://localhost:8080/ws?token={jwt}`. Supports `ping`, `pong`, `sync`, and real-time message push.

## Project Structure

```
slack-messaging/              (parent POM)
+-- common/                   Shared: entities, DTOs, ports, utilities
+-- auth-service/             Registration, login, JWT, user management
+-- channel-service/          Channels, DMs, membership
+-- message-service/          Messages, threads, reactions, unread counts
+-- media-service/            Presigned S3/MinIO upload URLs
+-- ws-gateway/               WebSocket connections, real-time fan-out
+-- api-gateway/              Reverse proxy, routing, static UI
+-- tests/browser/            Playwright browser tests (Dockerized)
+-- docs/                     16-file knowledge base
+-- docker-compose.yml        Full-stack local deployment
+-- test-e2e.sh               Curl-based E2E tests
+-- test-browser.sh           Multi-user browser tests (Playwright in Docker)
+-- CLAUDE.md                 AI agent instructions
```

## Knowledge Base

Detailed documentation lives in the `docs/` directory:

| Doc | What It Covers |
|-----|---------------|
| DOMAIN_KNOWLEDGE.md | 17 Slack features — exact behaviors, data model, edge cases |
| TRADEOFFS.md | 11 pre-decided architectural decisions |
| CODE_QUALITY.md | 10 quality standards, anti-patterns |
| EDGE_CASES.md | 30+ edge case patterns across 9 categories |
| ERROR_HANDLING.md | Exception hierarchy, fan-out errors, degradation matrix |
| FEATURE_WORKFLOW.md | 20-step cookbook for building any feature |
| FRONTEND_GUIDE.md | HTML demo client patterns, WS events, XSS prevention |
| NFR_GUIDE.md | Performance, monitoring, rate limiting, scale |
| ARCHITECTURE.md | System design, hexagonal layers, message delivery flow |
| DATABASE.md | Full schema (9 tables), migrations |
| API_DESIGN.md | REST conventions, response format |
| WEBSOCKET.md | 16 event types, delivery pipeline |
| CONVENTIONS.md | Naming, file locations, package structure |
| SECURITY.md | JWT, tenant isolation, BCrypt, rate limiting |
| TESTING.md | Build/deploy/test cycle |
| DEPLOYMENT.md | Docker, CI/CD, environment variables |

## License

MIT
