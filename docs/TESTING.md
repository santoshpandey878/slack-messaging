# Testing

## Test Types

### E2E Tests (test-e2e.sh)
**18 checks** across all 5 services. Run against the live Docker deployment.

```bash
./test-e2e.sh
```

Tests: register, login, wrong password, add user, create channel, add member, list channels, internal API (isMember, member-ids), send message, idempotency, history, unread, upload URL, content-type validation, WS upgrade, cross-tenant isolation, no-auth rejection.

### Adding E2E Tests

Follow the existing pattern in `test-e2e.sh`:
```bash
# Test name
RESULT=$(curl -s -X POST "http://localhost:$PORT/api/v1/endpoint" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"field":"value"}')

if echo "$RESULT" | grep -q '"success":true'; then
  pass "Test description"
else
  fail "Test description"
fi
```

### Unit Tests
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test
```

### Browser Tests (Playwright in Docker)
```bash
# Multi-user browser tests — no Node.js install needed, runs in Docker
./test-browser.sh
```

Tests real Chromium browser with two user contexts (like two incognito windows). Catches bugs that curl/E2E tests fundamentally cannot:
- **WS sender echo** — optimistic UI update + WS event = double-count for the acting user
- **Cross-user WS delivery** — messages/reactions/threads appearing in other users' tabs
- **DOM rendering** — badge counts, thread indicators, reaction displays

**Location:** `tests/browser/specs/*.spec.js`
**Infrastructure:** `tests/browser/Dockerfile` (Playwright official image with Chromium pre-installed)
**Helpers:** `tests/browser/helpers.js` (setupTwoUsers, loginViaUI, sendMessage, etc.)

**When to add browser tests:** Every feature with frontend UI changes, WebSocket events, or multi-user interaction MUST have browser tests. See FEATURE_WORKFLOW.md Step 12b.

### Integration Tests (requires Docker)
```bash
RUN_INTEGRATION_TESTS=true JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test
```

## Build & Deploy Cycle

```bash
# 1. Install parent + common
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -N -q
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -pl common -q

# 2. Package all services
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn package -DskipTests -q

# 3. Build Docker images
docker-compose build --quiet

# 4. Deploy (clean start for migration changes)
docker-compose down -v && docker-compose up -d

# 5. Wait for health
sleep 30

# 6. Verify
for PORT in 8080 8081 8082 8083 8084 8085; do
  STATUS=$(curl -s -m 5 "http://localhost:$PORT/actuator/health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','DOWN'))" 2>/dev/null || echo "DOWN")
  echo ":$PORT → $STATUS"
done

# 7. Run E2E
./test-e2e.sh
```

## Quick Iteration (no Docker rebuild)

If only changing Java code (no migration changes):
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -pl common -q && mvn package -DskipTests -q
docker-compose build --quiet && docker-compose up -d
```

If only changing one service:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn package -pl message-service -DskipTests -q
docker-compose build message-service && docker-compose up -d message-service
```

## Verifying a Feature Manually

```bash
# 1. Register + get token
REG=$(curl -s http://localhost:8081/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"tenantName":"Test","tenantSlug":"test-123","email":"a@t.com","displayName":"Admin","password":"pass123"}')
TOKEN=$(echo "$REG" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 2. Use token for API calls
curl -s http://localhost:8080/api/v1/channels \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"general","type":"PUBLIC"}' | python3 -m json.tool
```
