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
| API Docs         | springdoc-openapi (Swagger UI)  |
| Build            | Maven (multi-module)            |
| Containerization | Docker + Docker Compose         |

## Quick Start

### Prerequisites

- Java 11 (Amazon Corretto recommended)
- Maven 3.8+
- Docker and Docker Compose

### Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)

# Install parent POM and common module
mvn install -N -q && mvn install -pl common -q

# Package all services
mvn package -DskipTests -q
```

### Deploy via Docker

```bash
# Start everything (infra + all 6 services)
docker-compose build --quiet && docker-compose up -d

# Full reset (wipes all data)
docker-compose down -v && docker-compose up -d
```

### Verify Health

```bash
# API Gateway (aggregates all services)
curl http://localhost:8080/actuator/health

# Individual services
curl http://localhost:8081/actuator/health   # auth
curl http://localhost:8082/actuator/health   # channel
curl http://localhost:8083/actuator/health   # message
curl http://localhost:8084/actuator/health   # media
curl http://localhost:8085/actuator/health   # ws-gateway
```

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

## Demo UI

Open **http://localhost:8080** in your browser for the built-in chat client. Open two tabs to test real-time messaging between users.

## Testing

### Unit Tests

```bash
# Run all unit tests (~4 seconds, no Docker required)
mvn test

# Run a specific module's tests
mvn test -pl media-service
mvn test -pl ws-gateway
```

### E2E Tests

```bash
# Requires Docker containers running
docker-compose up -d
chmod +x test-e2e.sh && ./test-e2e.sh
```

## CI/CD

**GitHub Actions** -- add a workflow at `.github/workflows/ci.yml` to run `mvn test` on push and `docker-compose build` on merge to main.

**Local CD Watcher** -- use `start-all.sh` and `stop-all.sh` scripts for local development lifecycle.

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
+-- docker-compose.yml        Full-stack local deployment
+-- test-e2e.sh               Automated end-to-end test script
```

## Knowledge Base

Detailed documentation lives in the `docs/` directory:

- `ARCHITECTURE.md` -- System design, service boundaries, hexagonal layout
- `API_DESIGN.md` -- REST endpoint conventions
- `DATABASE.md` -- Schema, migrations, Flyway
- `WEBSOCKET.md` -- Real-time event protocol
- `SECURITY.md` -- Auth, tenant isolation, rate limiting
- `TESTING.md` -- Test strategy and coverage
- `DEPLOYMENT.md` -- Docker, CI/CD, environment config
- `CONVENTIONS.md` -- Code style, naming, file locations
- `FEATURE_WORKFLOW.md` -- Step-by-step guide for adding features

## Key Design Decisions

**Hexagonal Architecture (Ports and Adapters)** -- Business logic depends only on interfaces (ports). Infrastructure adapters (PostgreSQL, Redis, MinIO) are swappable without touching service code. Move from MinIO to S3, or Redis Pub/Sub to Kafka, by implementing a new adapter.

**Multi-Tenant Isolation** -- Every request carries `tenant_id` via JWT. `TenantContext` (thread-local) is set by `JwtAuthFilter` and enforced at every query layer. Tenants cannot see each other's data.

**Real-Time Delivery** -- Messages fan out via Redis Pub/Sub to WebSocket connections. Online users get instant push; offline users get unread counts incremented in Redis. Reconnection sync fetches missed messages from the database.

**Service Independence** -- Each microservice owns its own domain, exposes REST APIs, and communicates via HTTP (service ports). Cross-service calls use `ServiceClient` with retry logic. Services can be scaled, deployed, and restarted independently.

## License

MIT
