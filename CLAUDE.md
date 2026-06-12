# Slack Messaging System — AI Agent Instructions

## Operating Mode

You are an autonomous software engineer. When given a feature request:
1. **Do NOT ask clarifying questions.** All answers are in the knowledge base.
2. **Do NOT ask for permission.** You have full authority to create, modify, and delete files in this project.
3. **Do NOT make architectural decisions.** All decisions are pre-made in TRADEOFFS.md.
4. **Do NOT skip steps.** Follow FEATURE_WORKFLOW.md completely — every step, every checklist.
5. **Do NOT write dirty code.** Follow CODE_QUALITY.md for every line.
6. **Do NOT ignore edge cases.** Consult EDGE_CASES.md and DOMAIN_KNOWLEDGE.md.
7. **Build, test, deploy after every feature.** Run unit tests + E2E. Fix failures before moving on.
8. **NEVER delete or remove critical existing files** on the laptop outside this project.

## CRITICAL Technical Constraints

- **Java 11** — use `javax.persistence.*`, `javax.validation.*` (NEVER `jakarta.*`)
- **Spring Boot 2.7.18** — no Spring Boot 3.x features, no records, no sealed classes, no text blocks
- **Always rebuild common first:** `mvn install -N -q && mvn install -pl common -q`
- **Copy migrations to ALL 5 services** (auth, channel, message, media, ws-gateway)
- **Use `TenantContext`** for current user/tenant — never pass from handler params
- **All Redis keys via `RedisKeys.*`** — never hardcode key strings

## Knowledge Base — Read Order

When implementing a feature, read docs in this order:

### 1. Understand the Domain
| Doc | What You Learn |
|-----|---------------|
| [docs/DOMAIN_KNOWLEDGE.md](docs/DOMAIN_KNOWLEDGE.md) | What the feature IS, how Slack does it, exact behaviors, edge cases per feature |

### 2. Know the Rules
| Doc | What You Learn |
|-----|---------------|
| [docs/TRADEOFFS.md](docs/TRADEOFFS.md) | Pre-decided: consistency/availability, caching, pagination, auth model, transactions, delivery semantics |
| [docs/CODE_QUALITY.md](docs/CODE_QUALITY.md) | Method limits, null safety, exception rules, logging, anti-patterns, performance rules |
| [docs/EDGE_CASES.md](docs/EDGE_CASES.md) | Race conditions, null data, deletion cascades, security, pagination, WS edge cases, failure scenarios |
| [docs/ERROR_HANDLING.md](docs/ERROR_HANDLING.md) | Exception hierarchy, fan-out error pattern, inter-service failure handling, graceful degradation |

### 3. Build the Feature
| Doc | What You Learn |
|-----|---------------|
| [docs/FEATURE_WORKFLOW.md](docs/FEATURE_WORKFLOW.md) | **The 18-step cookbook.** Code → test → commit → push → Docker deploy → health check → E2E → verify. Full pipeline enforced. |

### 4. Frontend & NFR
| Doc | What You Learn |
|-----|---------------|
| [docs/FRONTEND_GUIDE.md](docs/FRONTEND_GUIDE.md) | HTML demo client patterns: API calls, WS event handlers, UI panels, message rendering, typing indicators, CSS variables, security (XSS prevention) |
| [docs/NFR_GUIDE.md](docs/NFR_GUIDE.md) | Non-functional requirements: performance, monitoring, rate limiting, data retention, caching, bulk operations, security hardening, scalability, load testing |

### 5. Reference (as needed)
| Doc | What You Learn |
|-----|---------------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System overview, hexagonal layers, service boundaries, message delivery flow |
| [docs/DATABASE.md](docs/DATABASE.md) | Full schema (9 tables), all columns, indexes, migration guide |
| [docs/API_DESIGN.md](docs/API_DESIGN.md) | All current endpoints, REST conventions, response format, routing |
| [docs/WEBSOCKET.md](docs/WEBSOCKET.md) | 18 event types, delivery pipeline, how to add events in 3 steps |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | Package structure, naming, file locations, imports |
| [docs/SECURITY.md](docs/SECURITY.md) | JWT, tenant isolation, rate limiting, BCrypt, public paths |
| [docs/TESTING.md](docs/TESTING.md) | Build/deploy/test cycle, E2E patterns |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, Colima, CI/CD, environment variables |

## Service Ownership

| Feature Area | Service | Port |
|-------------|---------|------|
| Auth, users, profiles | auth-service | 8081 |
| Channels, DMs, membership, topic | channel-service | 8082 |
| Messages, threads, reactions, pins, search, unread | message-service | 8083 |
| File uploads | media-service | 8084 |
| WebSocket, typing, presence | ws-gateway | 8085 |
| Reverse proxy, routing, demo UI | api-gateway | 8080 |

## Pre-Built Infrastructure (Use Before Creating New)

**DB columns (V4 migration — already exist):**
- `messages`: parent_message_id, reply_count, edited_at
- `channels`: topic, description
- `users`: avatar_url, status_text, timezone
- `channel_members`: muted, notification_level

**Tables (V4 migration — already exist):**
- `reactions` (message_id, user_id, emoji) with unique constraint
- `pinned_messages` (channel_id, message_id) with unique constraint
- `starred_items` (user_id, item_type, item_id) with unique constraint

**Entities (already exist in common/):**
- Reaction.java, PinnedMessage.java, StarredItem.java

**WS Event Types (WsEventType enum — 18 types pre-defined):**
- message.new, message.edited, message.deleted, thread.reply
- reaction.added, reaction.removed
- typing.start, typing.stop
- presence.change
- channel.updated, channel.archived
- member.joined, member.left
- pin.added, pin.removed
- read.receipt

**WS Payload Builders (WsPayloadBuilder — all pre-built):**
- buildMessageNew, buildMessageEdited, buildMessageDeleted
- buildThreadReply, buildReactionAdded, buildReactionRemoved
- buildTypingStart, buildPresenceChange
- buildChannelUpdated, buildMemberJoined, buildMemberLeft
- buildPinAdded, buildReadReceipt

**Fan-out (FanoutService — generic methods):**
- `fanoutEvent(tenantId, channelId, payload, excludeUserId, trackUnread)` — channel-scoped
- `fanoutToTenant(tenantId, payload, excludeUserId)` — tenant-wide (presence)

**ServiceClient (base class with all HTTP methods):**
- GET, POST, PUT, PATCH, DELETE

## Build Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
export PATH="/Users/santosh.pandey/apache-maven-3.8.6/bin:$PATH"

# Build all
mvn install -N -q && mvn install -pl common -q && mvn package -DskipTests -q

# Run unit tests
mvn test

# Deploy (clean, for migration changes)
docker-compose down -v && docker-compose build --quiet && docker-compose up -d

# Deploy (quick, no migration changes)
docker-compose build --quiet && docker-compose up -d

# E2E test
./test-e2e.sh

# Health check
for PORT in 8080 8081 8082 8083 8084 8085; do
  STATUS=$(curl -s -m 5 "http://localhost:$PORT/actuator/health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','DOWN'))" 2>/dev/null || echo "DOWN")
  echo ":$PORT → $STATUS"
done
```

## Full Pipeline — MANDATORY for Every Feature

Every feature MUST complete this entire pipeline. Do NOT stop at just writing code.

```
Code → Unit Tests → Commit → Push → Docker Deploy → Health Check → E2E → Verify
```

### 1. Build & Test
```bash
mvn install -N -q && mvn install -pl common -q && mvn package -DskipTests -q
mvn test    # ALL tests must pass
```

### 2. Commit & Push
```bash
git add -A
git commit -m "Add: {feature name}

- {what changed: endpoints, entities, migrations, tests}

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"

git push origin main
```

### 3. Deploy to Docker
```bash
# With new migration (new table/column):
docker-compose down -v && docker-compose build --quiet && docker-compose up -d

# Without migration change:
docker-compose build --quiet && docker-compose up -d
```

### 4. Health Check (wait up to 60s)
```bash
for i in 1 2 3 4 5 6; do
  sleep 10
  ALL_UP=true
  for PORT in 8080 8081 8082 8083 8084 8085; do
    STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
    [ "$STATUS" != "UP" ] && ALL_UP=false
  done
  if [ "$ALL_UP" = "true" ]; then break; fi
done
```

### 5. E2E Test
```bash
./test-e2e.sh    # ALL checks must pass (no regressions)
```

### 6. Verify New Feature
```bash
# Manually curl the new endpoint to confirm it works
```

**A feature is NOT done until all 6 steps complete successfully.**
**If any step fails, fix it before proceeding to the next feature.**
