# Feature Workflow — Complete Autonomous Build Guide

This document contains EVERYTHING needed to build any feature without asking questions. Follow every step. Skip nothing.

---

## Before You Start: Pre-Flight Checklist

Before writing any code for a new feature:

1. **Ensure CD watcher is running:**
```bash
# Check if already running
if [ -f .cd-watcher.pid ] && kill -0 $(cat .cd-watcher.pid) 2>/dev/null; then
  echo "CD Watcher running (PID $(cat .cd-watcher.pid))"
else
  echo "Starting CD Watcher..."
  ./scripts/cd-watcher.sh &
  sleep 2
  echo "CD Watcher started (PID $(cat .cd-watcher.pid))"
fi
```
**The CD watcher MUST be running before any feature work begins.** It auto-deploys after push.

2. **Ensure all services are healthy:**
```bash
for PORT in 8080 8081 8082 8083 8084 8085; do
  STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
  echo "  :$PORT → $STATUS"
done
```
If any service is DOWN, fix before starting feature work.

3. **Read DOMAIN_KNOWLEDGE.md** — understand what the feature IS, how Slack does it, edge cases
4. **Read TRADEOFFS.md** — every architectural decision is pre-made, follow them
5. **Read CODE_QUALITY.md** — quality standards, anti-patterns, validation rules
6. **Read EDGE_CASES.md** — race conditions, null handling, failure scenarios
7. **Identify the owning service** (see table below)
8. **Check if DB columns/tables already exist** in V4 migration (many are pre-built)
9. **Check if WS event type already exists** in WsEventType enum
10. **Check if WsPayloadBuilder method already exists**

### Service Ownership

| Feature Area | Service | Port |
|-------------|---------|------|
| Auth, users, profiles | auth-service | 8081 |
| Channels, DMs, membership, topic | channel-service | 8082 |
| Messages, threads, reactions, pins, search, unread | message-service | 8083 |
| File uploads | media-service | 8084 |
| Real-time delivery, typing, presence | ws-gateway | 8085 |
| Routing (only if new path prefix) | api-gateway | 8080 |

---

## The 18 Steps

### Step 1: Database Migration (if persistent data needed)

**File:** `{service}/src/main/resources/db/migration/V{N}__{description}.sql`

**Check first:** Many columns/tables already exist from V4. Run:
```sql
\d messages    -- check for parent_message_id, reply_count, edited_at
\d channels    -- check for topic, description
\d users       -- check for avatar_url, status_text, timezone
\dt reactions  -- check if table exists
\dt pinned_messages
\dt starred_items
```

**If migration needed:**
```sql
-- Always idempotent
ALTER TABLE messages ADD COLUMN IF NOT EXISTS new_field TYPE;
CREATE TABLE IF NOT EXISTS new_table (...);
CREATE INDEX IF NOT EXISTS idx_name ON table(columns);
```

**Copy to ALL 5 services:**
```bash
for svc in auth-service channel-service message-service media-service ws-gateway; do
  cp "$SRC" "$svc/src/main/resources/db/migration/"
done
```

**Rules:**
- Always include `tenant_id` for multi-tenancy
- Always add indexes for WHERE clause columns
- Use `IF NOT EXISTS` / `IF NOT EXISTS` everywhere
- Version number: check latest V*.sql and increment

### Step 2: Entity (in common/)

**File:** `common/src/main/java/com/slackmsg/domain/entity/{Entity}.java`

**Check first:** Entity may already exist (Reaction.java, PinnedMessage.java, StarredItem.java).

**For new columns on existing entity:** just add the field:
```java
@Column(name = "edited_at")
private Instant editedAt;
```

**For new entity:**
```java
package com.slackmsg.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;
import javax.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "table_name", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"col1", "col2"})  // if needed
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EntityName {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    // ... fields matching DB columns ...

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

**CRITICAL: Use `javax.persistence.*` NOT `jakarta.persistence.*`**

### Step 3: Repository (JPA, in owning service)

**File:** `{service}/src/main/java/com/slackmsg/{service}/adapter/postgres/{Entity}Repository.java`

```java
package com.slackmsg.message.adapter.postgres;

import com.slackmsg.domain.entity.Reaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface ReactionRepository extends JpaRepository<Reaction, UUID> {

    List<Reaction> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    boolean existsByMessageIdAndUserIdAndEmoji(UUID messageId, UUID userId, String emoji);

    void deleteByMessageIdAndUserIdAndEmoji(UUID messageId, UUID userId, String emoji);

    // Atomic counter update (race-safe)
    @Modifying
    @Query("UPDATE Message m SET m.replyCount = m.replyCount + 1 WHERE m.id = :id")
    void incrementReplyCount(@Param("id") UUID id);
}
```

**Rules:**
- Use Spring Data method name conventions for simple queries
- Use `@Query` for complex queries or atomic updates
- Use `@Modifying` with `@Query` for UPDATE/DELETE operations
- Always include `tenantId` in queries that touch multi-tenant data

### Step 4: Request DTO (in common/)

**File:** `common/src/main/java/com/slackmsg/dto/request/{Action}{Feature}Request.java`

```java
package com.slackmsg.dto.request;

import lombok.Data;
import javax.validation.constraints.*;
import java.util.UUID;

@Data
public class AddReactionRequest {
    @NotBlank(message = "Emoji is required")
    @Size(max = 100, message = "Emoji too long")
    private String emoji;
}
```

**Validation Rules (apply ALL that match):**
- Required strings: `@NotBlank`
- Required objects: `@NotNull`
- UUIDs: `@NotNull` (Spring auto-validates format)
- Strings with max length: `@Size(max = N)`
- Email: `@Email`
- Positive numbers: `@Positive`
- Size-limited content: `@Size(max = 40960)` (40KB for message content)
- Lists: `@NotEmpty` for required lists

### Step 5: Response DTO (in common/)

**File:** `common/src/main/java/com/slackmsg/dto/response/{Feature}Response.java`

```java
package com.slackmsg.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slackmsg.domain.entity.Reaction;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReactionResponse {
    private UUID id;
    private UUID messageId;
    private UUID userId;
    private String emoji;
    private Instant createdAt;

    public static ReactionResponse from(Reaction r) {
        return ReactionResponse.builder()
                .id(r.getId())
                .messageId(r.getMessageId())
                .userId(r.getUserId())
                .emoji(r.getEmoji())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
```

**Rules:**
- Always `@JsonInclude(JsonInclude.Include.NON_NULL)` — omit null fields
- Always static `from(Entity)` factory method
- Never expose internal fields (password_hash, etc.)

### Step 6: Service (business logic)

**File:** `{service}/src/main/java/com/slackmsg/{service}/service/{Feature}Service.java`

```java
package com.slackmsg.message.service;

import com.slackmsg.domain.entity.Reaction;
import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.message.adapter.postgres.ReactionRepository;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReactionService {

    private final ReactionRepository reactionRepo;
    private final ChannelServicePort channelService;
    private final FanoutService fanoutService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional
    public Reaction addReaction(UUID channelId, UUID messageId, AddReactionRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Authorization: must be channel member
        if (!channelService.isMember(channelId, userId)) {
            throw new SecurityException("Not a member of this channel");
        }

        // Validate message exists and is not deleted
        // (query message store or validate via existing endpoint)

        Reaction reaction = Reaction.builder()
                .tenantId(tenantId)
                .channelId(channelId)
                .messageId(messageId)
                .userId(userId)
                .emoji(req.getEmoji())
                .build();

        try {
            reaction = reactionRepo.save(reaction);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Already reacted with this emoji");
        }

        // Fan-out (best-effort)
        try {
            String payload = com.slackmsg.util.WsPayloadBuilder.buildReactionAdded(
                    tenantId, channelId, messageId, userId, req.getEmoji(), objectMapper);
            fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false);
        } catch (Exception e) {
            log.error("Reaction fanout failed: {}", e.getMessage());
        }

        log.info("Reaction added: msgId={} userId={} emoji={}", messageId, userId, req.getEmoji());
        return reaction;
    }
}
```

**Service Rules:**
- `@RequiredArgsConstructor` + `private final` for ALL dependencies
- Use `TenantContext.getTenantId()` / `getUserId()` — never pass from handler
- Check membership for channel operations: `channelService.isMember()`
- Check authorization for admin operations: use `AuthorizationService`
- Wrap fan-out in try-catch (best-effort)
- `@Transactional` for writes, `@Transactional(readOnly = true)` for reads
- Max 100 lines. Split if larger.
- Log ONE line per operation at INFO level

### Step 7: Handler (REST endpoint)

**File:** `{service}/src/main/java/com/slackmsg/{service}/handler/{Feature}Handler.java`

```java
package com.slackmsg.message.handler;

import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.dto.response.ReactionResponse;
import com.slackmsg.message.service.ReactionService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channels/{channelId}/messages/{messageId}/reactions")
@RequiredArgsConstructor
public class ReactionHandler {

    private final ReactionService reactionService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReactionResponse>> add(
            @PathVariable UUID channelId,
            @PathVariable UUID messageId,
            @Valid @RequestBody AddReactionRequest request) {
        var reaction = reactionService.addReaction(channelId, messageId, request);
        return ResponseEntity.ok(ApiResponse.ok("Reaction added", ReactionResponse.from(reaction)));
    }
}
```

**Handler Rules:**
- THIN — no business logic, just extract params + call service + return ApiResponse
- Always `@Valid` on `@RequestBody`
- Always return `ResponseEntity<ApiResponse<T>>`
- Use `@PathVariable` for UUIDs (Spring auto-validates format)

### Step 8: API Gateway Route (if needed)

**File:** `api-gateway/src/main/java/com/slackmsg/gateway/config/ServiceRoutes.java`

**Check first:** Most `/api/v1/channels/{id}/*` paths already route to the correct service. Only add a route if you have a completely new path prefix.

### Step 9: WebSocket Event (if real-time notification needed)

**9a. Check WsEventType enum** — type may already exist
**9b. Check WsPayloadBuilder** — builder may already exist
**9c. If new type needed**, add to enum and builder (see WEBSOCKET.md)
**9d. Fan out from service:**

```java
// Channel-scoped event (reactions, pins, threads, edits, deletes)
String payload = WsPayloadBuilder.buildXxx(tenantId, channelId, ...data..., objectMapper);
fanoutService.fanoutEvent(tenantId, channelId, payload, excludeUserId, trackUnread);

// Tenant-scoped event (presence changes)
String payload = WsPayloadBuilder.buildPresenceChange(tenantId, userId, status, objectMapper);
fanoutService.fanoutToTenant(tenantId, payload, excludeUserId);
```

**Fan-out parameters:**
- `excludeUserId`: the user who triggered the action (they don't need their own event). null = send to all.
- `trackUnread`: `true` for messages (increment offline unread count). `false` for everything else (reactions, typing, presence, edits).

### Step 10: Internal API (if cross-service data needed)

**File:** `{service}/src/main/java/com/slackmsg/{service}/handler/{Service}InternalHandler.java`

Internal endpoints are at `/internal/*` — excluded from JWT auth. Used only by other services via `ServiceClient`.

### Step 11: Unit Tests

**File:** `{service}/src/test/java/com/slackmsg/{service}/service/{Feature}ServiceTest.java`

**Test cases for EVERY new service method:**
- [ ] Happy path (valid input, authorized user)
- [ ] Membership check fails → SecurityException
- [ ] Authorization check fails → SecurityException
- [ ] Resource not found → IllegalArgumentException
- [ ] Duplicate (unique constraint) → graceful handling
- [ ] Deleted/archived resource → blocked
- [ ] Fan-out failure → operation still succeeds
- [ ] Input validation → IllegalArgumentException

**Template:**
```java
@ExtendWith(MockitoExtension.class)
class ReactionServiceTest {

    @Mock private ReactionRepository reactionRepo;
    @Mock private ChannelServicePort channelService;
    @Mock private FanoutService fanoutService;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private ReactionService reactionService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
        TenantContext.setDisplayName("Test User");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void addReaction_success() { ... }

    @Test
    void addReaction_notMember() {
        when(channelService.isMember(any(), any())).thenReturn(false);
        assertThrows(SecurityException.class, () ->
            reactionService.addReaction(channelId, messageId, request));
    }
}
```

### Step 12: E2E Test

**File:** Add test cases to `test-e2e.sh`

Follow existing pattern:
```bash
# --- REACTIONS ---
echo "--- REACTIONS ---"
REACTION=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages/$MSG_ID/reactions" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"emoji":"+1"}')
check "$REACTION" "Add reaction"
```

### Step 13: Update HTML Demo Client (if UI-visible feature)

**File:** `api-gateway/src/main/resources/static/index.html`

**Read `docs/FRONTEND_GUIDE.md` first** — it has exact patterns for:
- Adding API call functions (use `api()` helper)
- Handling new WS event types in `ws.onmessage`
- Adding UI panels (modal dialogs)
- Adding sidebar/topbar buttons
- Updating message rendering (edited indicator, thread count, reactions)
- Typing indicator display
- CSS variables (use vars, don't hardcode colors)
- XSS prevention (always `escapeHtml()`)

Complete the **Feature UI Checklist** from FRONTEND_GUIDE.md before moving on.

### Step 14: Build & Run Unit Tests (LOCAL)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
export PATH="/Users/santosh.pandey/apache-maven-3.8.6/bin:$PATH"

# Build all modules
mvn install -N -q && mvn install -pl common -q && mvn package -DskipTests -q

# Run unit tests — ALL must pass before proceeding
mvn test
```

**If tests fail:** Fix the issue. Do NOT proceed until all tests pass.

### Step 15: Deploy Locally & Verify (LOCAL — before any commit)

```bash
# If new migration was added (new table/column): clean deploy
docker-compose down -v && docker-compose build --quiet && docker-compose up -d

# If no migration change: quick redeploy
docker-compose build --quiet && docker-compose up -d

# Wait for all services to be healthy (up to 60s)
for i in 1 2 3 4 5 6; do
  sleep 10
  ALL_UP=true
  for PORT in 8080 8081 8082 8083 8084 8085; do
    STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
    [ "$STATUS" != "UP" ] && ALL_UP=false
  done
  if [ "$ALL_UP" = "true" ]; then break; fi
done

# ALL 6 must show UP
for PORT in 8080 8081 8082 8083 8084 8085; do
  STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
  echo "  :$PORT → $STATUS"
done
```

**If any service is DOWN:** Check `docker-compose logs {service-name}`. Fix and redeploy. Do NOT commit broken code.

### Step 16: Run E2E + Manual Verification (LOCAL — before any commit)

```bash
# Run existing E2E suite (must pass — no regressions)
./test-e2e.sh

# Manually test the NEW feature with curl
TOKEN=$(curl -s http://localhost:8081/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"tenantName":"Test","tenantSlug":"test-'$(date +%s)'","email":"a@t.com","displayName":"Admin","password":"pass123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# Test each new endpoint — verify happy path + error cases
curl -s http://localhost:8080/api/v1/{new-endpoint} \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{...}' | python3 -m json.tool
```

**CRITICAL:** Test EVERY new endpoint manually — happy path AND error cases (not member, not found, duplicate, deleted resource).

**If anything fails:** Fix the code, re-run unit tests (Step 14), redeploy (Step 15), re-verify (Step 16). Loop until everything works locally.

### Step 16b: Update README.md (MANDATORY — before commit)

**File:** `README.md`

- **Adding a feature:** Add it to the features list AND add a "How to Test" section with step-by-step demo instructions
- **Removing a feature:** Remove it from the features list AND remove its test instructions
- This is NOT optional — README must always reflect the current state of the system before committing

**How to Test section format:**
```markdown
### How to Test: {Feature Name}
1. Open http://localhost:8080
2. Login/Register as User A
3. {Step-by-step instructions to demo the feature}
4. {Expected result at each step}
5. (Optional) Open incognito tab, login as User B, verify multi-user behavior
```
These instructions serve as the **demo script** — during the Harinder call, follow these steps to showcase the feature.

### Step 17: Commit & Push to GitHub (only after local verification passes)

```bash
git add -A

git commit -m "Add: {feature name}

- {what was added: endpoints, entities, migrations}
- {tests: N unit tests added}
- {WS events: event types used}

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"

git push origin main
```

**Commit rules:**
- Start with `Add:` for new features, `Fix:` for bugs, `Update:` for enhancements
- Only commit AFTER local unit tests + E2E + manual verification all pass
- Never commit broken or untested code

### Step 18: CI (GitHub Actions) — Wait & Fix if Needed

After pushing, GitHub Actions CI runs automatically (.github/workflows/ci.yml):
1. Builds all modules
2. Runs unit tests
3. Runs E2E tests (with PG + Redis service containers)

**Monitor CI:**
```bash
# Check CI status (requires gh CLI)
gh run list --limit 1

# Or check at: https://github.com/santoshpandey878/slack-messaging/actions
```

**If CI fails:**
1. Read the failure log: `gh run view --log-failed`
2. Fix the issue locally
3. Re-run unit tests + E2E locally
4. Commit the fix and push again
5. Wait for CI to pass

**Do NOT proceed until CI is green.**

### Step 19: CD Auto-Deploys Locally (MANDATORY — wait for it)

The CD watcher (started in Pre-Flight) auto-detects the push within 30 seconds:
1. Detects new commit on main
2. `git pull`
3. `mvn package`
4. `docker-compose build + up`
5. Health check (all 6 services)
6. Run E2E tests
7. Log results to `logs/cd-watcher.log`

**Wait for CD to complete and verify:**
```bash
# Wait up to 120s for CD to finish deploying
echo "Waiting for CD watcher to deploy..."
for i in $(seq 1 12); do
  sleep 10
  if tail -5 logs/cd-watcher.log 2>/dev/null | grep -q "DEPLOY SUCCESS"; then
    echo "CD deployment successful!"
    break
  fi
  echo "  Waiting... ($((i*10))s)"
done

# Show CD result
tail -10 logs/cd-watcher.log
```

**If CD fails:** Check `logs/cd-watcher.log` for the error. Fix locally, push again.

### Step 20: Final Verification on CD-Deployed Version — Demo Ready

```bash
# 1. Confirm CD deployed the latest commit
echo "Deployed version:"
curl -s http://localhost:8080/version | python3 -m json.tool

# 2. Confirm git commit matches
echo "Latest commit:"
git log --oneline -1

# 3. E2E on CD-deployed version (not local build — the CD-built version)
./test-e2e.sh

# 4. Quick-test the new feature on CD-deployed version
TOKEN=$(curl -s http://localhost:8081/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"tenantName":"CDVerify","tenantSlug":"cd-'$(date +%s)'","email":"a@t.com","displayName":"Admin","password":"pass123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
# Test new endpoints on CD-deployed version...

# 5. Demo ready
echo "Demo ready at: http://localhost:8080"
```

**Feature is DONE only when ALL of these are true:**
1. Unit tests pass locally (Phase 1)
2. Feature verified via curl AND browser locally (Phase 1)
3. Committed and pushed to GitHub
4. CI (GitHub Actions) green — checked via `gh run list`
5. CD watcher auto-deployed — confirmed in `logs/cd-watcher.log` showing "DEPLOY SUCCESS"
6. `/version` endpoint returns latest on CD-deployed instance
7. E2E passes on CD-deployed version (18+ checks, 0 failed)
8. New feature works on CD-deployed version (curl verified)

---

## Post-Build Quality Checklist

After implementing a feature, verify ALL of these BEFORE committing:

### Code Quality
- [ ] **Tenant isolation:** All queries include `tenant_id`
- [ ] **Authorization:** Membership/admin checked for every operation
- [ ] **Validation:** All inputs validated (required fields, size limits, format)
- [ ] **Soft delete respected:** Queries filter `is_deleted = false` where applicable
- [ ] **Archived channels:** Blocked from writes where applicable
- [ ] **Race conditions:** Unique constraints + `DataIntegrityViolationException` handling
- [ ] **Atomic counters:** SQL `SET count = count + 1` (not read-modify-write)
- [ ] **Fan-out wrapped:** try-catch around fan-out (best-effort)
- [ ] **Transaction scope:** No REST/Redis calls inside DB transaction
- [ ] **Null safety:** All nullable fields handled
- [ ] **Logging:** One INFO log per public method call
- [ ] **No hardcoded keys:** Redis keys use `RedisKeys.*` methods
- [ ] **Response format:** `ApiResponse<T>` wrapper, `@JsonInclude(NON_NULL)`
- [ ] **Pagination:** Limit clamped to [1, 100] for list endpoints

### Local Verification (BEFORE commit)
- [ ] **Unit tests pass:** `mvn test` — 0 failures
- [ ] **Docker deployed locally:** `docker-compose build && up -d`
- [ ] **All 6 services healthy:** health check on ports 8080-8085
- [ ] **E2E tests pass:** `./test-e2e.sh` — 0 failures
- [ ] **New feature manually tested:** curl test every new endpoint (happy + error paths)
- [ ] **Frontend tested:** open http://localhost:8080, test feature in browser
- [ ] **No regressions:** existing E2E checks still pass

### Git + CI/CD (AFTER local verification)
- [ ] **Committed:** `git add -A && git commit`
- [ ] **Pushed:** `git push origin main`
- [ ] **CI passes:** `gh run list --limit 1` shows success
- [ ] **CI fix:** if CI fails, fix locally, re-verify, push fix, re-check CI
- [ ] **CD auto-deployed:** `tail -10 logs/cd-watcher.log` shows "DEPLOY SUCCESS"
- [ ] **Version verified:** `curl http://localhost:8080/version` shows latest
- [ ] **E2E on CD version:** `./test-e2e.sh` — 0 failures on CD-deployed build
- [ ] **Feature on CD version:** curl test new endpoints on CD-deployed build

### Definition of Done
A feature is COMPLETE only when:
1. Code written following all quality standards
2. Unit tests written and passing locally
3. Docker deployed and all 6 services healthy locally
4. E2E tests pass locally (including new test cases)
5. Feature manually verified via curl AND browser demo UI
6. Committed and pushed to GitHub
7. CI (GitHub Actions) green — `gh run list` confirmed
8. CD watcher auto-deployed — `logs/cd-watcher.log` shows DEPLOY SUCCESS
9. `/version` endpoint confirms latest version on CD-deployed instance
10. E2E passes on CD-deployed version
11. Feature verified on CD-deployed version — demo ready
