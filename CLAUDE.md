# Slack Messaging System — AI Agent Instructions

## What This Is

A Slack-like messaging system with 6 microservices + shared library. Hexagonal architecture, multi-tenant, real-time WebSocket delivery.

## CRITICAL: Read Before Doing Anything

- **Java 11** — use `javax.persistence.*`, `javax.validation.*` (NEVER `jakarta.*`)
- **Spring Boot 2.7.18** — no Spring Boot 3.x features
- **Always rebuild common first:** `mvn install -N -q && mvn install -pl common -q`
- **Copy migrations to ALL 5 services** (auth, channel, message, media, ws-gateway)
- **Use `TenantContext`** for current user/tenant — never pass from handler params

## Knowledge Base

Read these docs before implementing any feature:

| Doc | When to Read |
|-----|-------------|
| [docs/FEATURE_WORKFLOW.md](docs/FEATURE_WORKFLOW.md) | **ALWAYS — step-by-step cookbook for adding ANY feature** |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Understanding system structure, service boundaries |
| [docs/DATABASE.md](docs/DATABASE.md) | Adding tables, columns, migrations |
| [docs/API_DESIGN.md](docs/API_DESIGN.md) | Adding REST endpoints |
| [docs/WEBSOCKET.md](docs/WEBSOCKET.md) | Adding real-time events |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | Coding patterns, naming, file locations |
| [docs/SECURITY.md](docs/SECURITY.md) | Auth, tenant isolation, rate limiting |
| [docs/TESTING.md](docs/TESTING.md) | Building, deploying, testing |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, CI/CD, environment |

## Quick Start: Adding a Feature

1. Read `docs/FEATURE_WORKFLOW.md` — follow the 11 steps
2. Identify which service owns the feature (see ARCHITECTURE.md)
3. Create migration → entity → repository → DTO → service → handler → WS event → test
4. Build: `mvn install -pl common -q && mvn package -DskipTests -q`
5. Deploy: `docker-compose build --quiet && docker-compose up -d`
6. Test: `./test-e2e.sh`

## Service Ownership

| Feature | Service | Port |
|---------|---------|------|
| Users, auth, profiles | auth-service | 8081 |
| Channels, DMs, membership | channel-service | 8082 |
| Messages, threads, reactions, pins, search, unread | message-service | 8083 |
| File uploads | media-service | 8084 |
| WebSocket connections | ws-gateway | 8085 |
| Reverse proxy, routing | api-gateway | 8080 |

## Pre-Built Infrastructure (Ready to Use)

These are already built — just add service logic + endpoints:

- **Thread columns:** `messages.parent_message_id`, `messages.reply_count` (V4 migration)
- **Edit column:** `messages.edited_at` (V4 migration)
- **Channel metadata:** `channels.topic`, `channels.description` (V4 migration)
- **User profile:** `users.avatar_url`, `users.status_text`, `users.timezone` (V4 migration)
- **Notification prefs:** `channel_members.muted`, `channel_members.notification_level` (V4 migration)
- **Reactions table:** `reactions` with unique(message_id, user_id, emoji) (V4 migration)
- **Pinned messages table:** `pinned_messages` with unique(channel_id, message_id) (V4 migration)
- **Starred items table:** `starred_items` with unique(user_id, item_type, item_id) (V4 migration)
- **Entities:** `Reaction.java`, `PinnedMessage.java`, `StarredItem.java` in common
- **WS Event Types:** 18 types in `WsEventType.java` enum
- **WS Payload Builders:** Pre-built for message.new/edited/deleted, thread.reply, reaction.added/removed, typing, presence, channel.updated, member.joined/left, pin, read.receipt
- **Generic Fan-out:** `FanoutService.fanoutEvent()` (channel-scoped), `fanoutToTenant()` (tenant-scoped)
- **ServiceClient:** GET, POST, PUT, PATCH, DELETE for inter-service calls

## Build Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
export PATH="/Users/santosh.pandey/apache-maven-3.8.6/bin:$PATH"

# Compile all
mvn install -N -q && mvn install -pl common -q && mvn compile

# Package all
mvn package -DskipTests -q

# Deploy
docker-compose build --quiet && docker-compose up -d

# Test
./test-e2e.sh

# Full reset (wipes DB)
docker-compose down -v && docker-compose up -d
```
