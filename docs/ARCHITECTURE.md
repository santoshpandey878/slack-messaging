# Architecture

## System Overview

Slack-like messaging system: 6 microservices + 1 shared library, all in one Maven multi-module repo.

```
Client (Browser)
    │
    ▼
API Gateway (:8080)  ─── reverse proxy + WS proxy
    │
    ├─► Auth Service (:8081)       ─── register, login, user management
    ├─► Channel Service (:8082)    ─── channels, DMs, membership
    ├─► Message Service (:8083)    ─── messages, threads, fan-out, unread
    ├─► Media Service (:8084)      ─── presigned URL file upload
    └─► WS Gateway (:8085)        ─── WebSocket real-time delivery

Infrastructure:
    PostgreSQL (:5432)  ─── shared database (all services)
    Redis (:6379)       ─── cache, pub/sub, rate limiting, connection registry
    MinIO (:9000)       ─── S3-compatible object storage
```

## Maven Modules

```
slack-messaging/                    ← parent POM
├── common/                         ← shared library (NOT a Spring Boot app)
│   ├── domain/entity/              ← JPA entities (Message, Channel, User, etc.)
│   ├── domain/enums/               ← MessageType, ChannelType, MemberRole, WsEventType
│   ├── dto/request/                ← REST request DTOs
│   ├── dto/response/               ← REST response DTOs
│   ├── port/repository/            ← storage interfaces (MessageStore, ChannelStore, etc.)
│   ├── port/service/               ← service interfaces (PubSubService, CacheService, etc.)
│   ├── adapter/redis/              ← Redis implementations of ports
│   ├── middleware/                  ← JwtAuthFilter, RateLimitFilter, GlobalExceptionHandler
│   ├── client/                     ← ServiceClient base class for inter-service REST
│   └── util/                       ← JwtUtil, TenantContext, WsPayloadBuilder, RedisKeys, ApiResponse
├── auth-service/                   ← Spring Boot app, port 8081
├── channel-service/                ← Spring Boot app, port 8082
├── message-service/                ← Spring Boot app, port 8083
├── media-service/                  ← Spring Boot app, port 8084
├── ws-gateway/                     ← Spring Boot app, port 8085
├── api-gateway/                    ← Spring Boot app, port 8080
├── docker-compose.yml              ← all infra + services
├── test-e2e.sh                     ← 18-check E2E test script
└── docs/                           ← this knowledge base
```

## Hexagonal Architecture (Ports & Adapters)

Every service follows the same layered pattern:

```
┌─────────────────────────────────────────────────────┐
│ HANDLER (thin REST controller)                       │
│   - Extracts params from request                     │
│   - Calls service method                             │
│   - Returns ApiResponse                              │
│   - NO business logic                                │
├─────────────────────────────────────────────────────┤
│ SERVICE (business logic, <100 lines each)            │
│   - Single Responsibility                            │
│   - Calls ports (interfaces), never implementations  │
│   - Uses TenantContext for current user/tenant        │
├─────────────────────────────────────────────────────┤
│ PORT (interface)                                     │
│   - Repository ports: MessageStore, ChannelStore     │
│   - Service ports: CacheService, PubSubService       │
│   - Inter-service: ChannelServicePort                │
├─────────────────────────────────────────────────────┤
│ ADAPTER (implementation)                             │
│   - adapter/postgres/ → JPA repositories             │
│   - adapter/redis/ → Redis operations                │
│   - adapter/s3/ → MinIO/S3 presigned URLs            │
│   - client/ → REST client for inter-service          │
└─────────────────────────────────────────────────────┘
```

**Key rule:** Services depend on PORTS (interfaces), never on adapters directly. This allows swapping PostgreSQL → Cassandra, Redis → Memcached, etc. without changing business logic.

## Service Responsibilities

| Service | Port | Owns | Depends On |
|---------|------|------|------------|
| **auth-service** | 8081 | tenants, users, JWT, login | PostgreSQL, Redis (login guard) |
| **channel-service** | 8082 | channels, DMs, membership | PostgreSQL |
| **message-service** | 8083 | messages, fan-out, unread, idempotency | PostgreSQL, Redis, channel-service (REST) |
| **media-service** | 8084 | presigned upload URLs | MinIO/S3 |
| **ws-gateway** | 8085 | WebSocket connections, real-time delivery | Redis (pub/sub + registry), channel-service + message-service (REST) |
| **api-gateway** | 8080 | reverse proxy, WS proxy, static UI | All backend services |

## Inter-Service Communication

Services communicate via REST through `/internal/*` endpoints (excluded from JWT auth):

```
message-service ──REST──► channel-service
    /internal/channels/{id}/is-member/{userId}
    /internal/channels/{id}/member-ids

ws-gateway ──REST──► channel-service
    /internal/channels/{id}/is-member/{userId}

ws-gateway ──REST──► message-service
    /internal/messages/after/{channelId}/{afterMessageId}
```

**Pattern:** Each service has a `client/XxxServiceClient.java` that extends `ServiceClient` and implements the corresponding port interface (e.g., `ChannelServicePort`).

## Message Delivery Flow

```
1. Client sends POST /api/v1/channels/{id}/messages
2. API Gateway proxies to message-service
3. MessageService validates membership (REST call to channel-service)
4. MessageService persists to PostgreSQL
5. FanoutService checks Redis for each member's connection
6. Online members: publish to Redis Pub/Sub "ws:server:{serverId}"
7. Offline members: increment unread count in Redis
8. WS Gateway receives Pub/Sub message
9. WsHandler delivers to local WebSocket sessions (membership-checked)
10. Client receives real-time JSON via WebSocket
```

## Multi-Tenancy

Every request is tenant-scoped:
1. JWT contains `tenantId` claim
2. `JwtAuthFilter` extracts and sets `TenantContext` (ThreadLocal)
3. All database queries filter by `tenant_id`
4. All Redis keys include `tenantId`
5. WebSocket delivery checks tenant prefix before sending

## Technology Stack

- **Java 11** (Amazon Corretto) — MUST use `javax.*`, NOT `jakarta.*`
- **Spring Boot 2.7.18** — `javax.persistence`, `javax.validation`
- **Maven** — multi-module build
- **PostgreSQL 14** — shared relational database
- **Redis 7** — cache, pub/sub, rate limiting
- **MinIO** — S3-compatible object storage
- **Docker + Colima** — containerized deployment on macOS
