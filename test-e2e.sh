#!/bin/bash
# ═══════════════════════════════════════════════════
# Slack Messaging — 5-Service E2E Test (20 checks)
#
# Prerequisites: all 5 services running
#   ./start-all.sh  OR  start each manually
#
# Usage: ./test-e2e.sh
# ═══════════════════════════════════════════════════
set -e
PASS=0; FAIL=0
check() { if echo "$2" | grep -q "$1"; then echo "  ✅ $3"; PASS=$((PASS+1)); else echo "  ❌ $3"; FAIL=$((FAIL+1)); fi }

SLUG="test-$$"
echo "═══ 5-SERVICE E2E TEST (slug=$SLUG) ═══"

echo ""; echo "--- AUTH (8081) ---"
REG=$(curl -s -X POST http://localhost:8081/api/v1/auth/register -H "Content-Type: application/json" \
  -d "{\"tenantName\":\"$SLUG\",\"tenantSlug\":\"$SLUG\",\"email\":\"admin@$SLUG.com\",\"displayName\":\"Admin\",\"password\":\"test123456\"}")
check '"success":true' "$REG" "Register tenant"
TOKEN=$(echo $REG | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)

LOGIN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login -H "Content-Type: application/json" \
  -d "{\"tenantSlug\":\"$SLUG\",\"email\":\"admin@$SLUG.com\",\"password\":\"test123456\"}")
check '"success":true' "$LOGIN" "Login"

WRONG=$(curl -s -X POST http://localhost:8081/api/v1/auth/login -H "Content-Type: application/json" \
  -d "{\"tenantSlug\":\"$SLUG\",\"email\":\"admin@$SLUG.com\",\"password\":\"wrong\"}")
check '"success":false' "$WRONG" "Wrong password"

USER2=$(curl -s -X POST http://localhost:8081/api/v1/users -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"email\":\"bob@$SLUG.com\",\"displayName\":\"Bob\",\"password\":\"test123456\"}")
check '"success":true' "$USER2" "Add user"
USER2_ID=$(echo $USER2 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['userId'])" 2>/dev/null)

echo ""; echo "--- CHANNEL (8082) ---"
CH=$(curl -s -X POST http://localhost:8082/api/v1/channels -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"general","type":"PUBLIC"}')
check '"success":true' "$CH" "Create channel"
CH_ID=$(echo $CH | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)

ADD=$(curl -s -X POST "http://localhost:8082/api/v1/channels/$CH_ID/members" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"userIds\":[\"$USER2_ID\"]}")
check '"success":true' "$ADD" "Add member"

LIST=$(curl -s http://localhost:8082/api/v1/channels -H "Authorization: Bearer $TOKEN")
check '"general"' "$LIST" "List channels"

echo ""; echo "--- INTERNAL (8082) ---"
USER1_ID=$(echo $TOKEN | python3 -c "import sys,base64,json; t=sys.stdin.read().split('.')[1]; t+='='*(4-len(t)%4); print(json.loads(base64.urlsafe_b64decode(t))['sub'])" 2>/dev/null)
ISMEMBER=$(curl -s "http://localhost:8082/internal/channels/$CH_ID/is-member/$USER1_ID")
check '"isMember":true' "$ISMEMBER" "isMember=true"

MIDS=$(curl -s "http://localhost:8082/internal/channels/$CH_ID/member-ids")
check "$USER2_ID" "$MIDS" "member-ids has Bob"

echo ""; echo "--- MESSAGE (8083) ---"
MSG=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"content\":\"E2E test message\",\"idempotencyKey\":\"e2e-$SLUG\"}")
check '"success":true' "$MSG" "Send message"
MSG_ID=$(echo $MSG | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)

MSG2=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"content\":\"E2E test message\",\"idempotencyKey\":\"e2e-$SLUG\"}")
check "$MSG_ID" "$MSG2" "Idempotency"

HIST=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/messages?limit=10" -H "Authorization: Bearer $TOKEN")
check '"E2E test message"' "$HIST" "History"

TOKEN2=$(curl -s -X POST http://localhost:8081/api/v1/auth/login -H "Content-Type: application/json" \
  -d "{\"tenantSlug\":\"$SLUG\",\"email\":\"bob@$SLUG.com\",\"password\":\"test123456\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
UNREAD=$(curl -s "http://localhost:8083/api/v1/unread" -H "Authorization: Bearer $TOKEN2")
check "$CH_ID" "$UNREAD" "Bob unread"

echo ""; echo "--- MEDIA (8084) ---"
UP=$(curl -s -X POST http://localhost:8084/api/v1/media/upload-url -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"fileName":"test.png","contentType":"image/png","sizeBytes":1024}')
check '"uploadUrl"' "$UP" "Upload URL"

INV=$(curl -s -X POST http://localhost:8084/api/v1/media/upload-url -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"fileName":"x.exe","contentType":"application/x-executable","sizeBytes":1024}')
check '"success":false' "$INV" "Invalid type rejected"

echo ""; echo "--- WS GATEWAY (8085) ---"
WS=$(curl -s -o /dev/null -w "%{http_code}" -H "Upgrade: websocket" -H "Connection: Upgrade" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Sec-WebSocket-Version: 13" \
  "http://localhost:8085/ws?token=$TOKEN" --max-time 2 2>&1 || true)
check "101" "$WS" "WS upgrade 101"

echo ""; echo "--- CROSS-TENANT ---"
REG3=$(curl -s -X POST http://localhost:8081/api/v1/auth/register -H "Content-Type: application/json" \
  -d "{\"tenantName\":\"other\",\"tenantSlug\":\"other-$SLUG\",\"email\":\"x@other.com\",\"displayName\":\"X\",\"password\":\"test123456\"}")
TK3=$(echo $REG3 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
CROSS=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages" -H "Content-Type: application/json" -H "Authorization: Bearer $TK3" \
  -d '{"content":"snoop"}')
check '"success":false' "$CROSS" "Cross-tenant blocked"

NOAUTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/api/v1/channels)
check "401" "$NOAUTH" "No auth → 401"

echo ""; echo "--- THREADS (8083) ---"
REPLY=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "{\"content\":\"Thread reply\",\"parentMessageId\":\"$MSG_ID\",\"idempotencyKey\":\"thread-$SLUG\"}")
check '"success":true' "$REPLY" "Send thread reply"
check '"parentMessageId"' "$REPLY" "Reply has parentMessageId"
THREAD=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/threads/$MSG_ID?limit=10" -H "Authorization: Bearer $TOKEN")
check '"Thread reply"' "$THREAD" "Get thread replies"
HIST2=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/messages?limit=10" -H "Authorization: Bearer $TOKEN")
REPLY_IN_HIST=$(echo "$HIST2" | grep -c "Thread reply" || true)
if [ "$REPLY_IN_HIST" = "0" ]; then echo "  ✅ Thread reply excluded from history"; PASS=$((PASS+1)); else echo "  ❌ Thread reply leaked into history"; FAIL=$((FAIL+1)); fi

echo ""; echo "--- REACTIONS (8083) ---"
REACT=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages/$MSG_ID/reactions" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"emoji":"+1"}')
check '"success":true' "$REACT" "Add reaction"
REACTIONS=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/messages/$MSG_ID/reactions" -H "Authorization: Bearer $TOKEN")
check '"+1"' "$REACTIONS" "Get reactions"
REACT_DUP=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/messages/$MSG_ID/reactions" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"emoji":"+1"}')
check '"success":false' "$REACT_DUP" "Duplicate reaction blocked"
UNREACT=$(curl -s -X DELETE "http://localhost:8083/api/v1/channels/$CH_ID/messages/$MSG_ID/reactions/+1" -H "Authorization: Bearer $TOKEN")
check '"success":true' "$UNREACT" "Remove reaction"

echo ""; echo "--- PINS (8083) ---"
PIN=$(curl -s -X POST "http://localhost:8083/api/v1/channels/$CH_ID/pins/$MSG_ID" -H "Authorization: Bearer $TOKEN")
check '"success":true' "$PIN" "Pin message"
PINS=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/pins" -H "Authorization: Bearer $TOKEN")
check "$MSG_ID" "$PINS" "List pins"
UNPIN=$(curl -s -X DELETE "http://localhost:8083/api/v1/channels/$CH_ID/pins/$MSG_ID" -H "Authorization: Bearer $TOKEN")
check '"success":true' "$UNPIN" "Unpin message"

sleep 2  # rate limit cooldown
echo ""; echo "--- SEARCH (8083) ---"
SEARCH=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/search?q=E2E&limit=10" -H "Authorization: Bearer $TOKEN")
check '"E2E test message"' "$SEARCH" "Search messages"
SEARCH_EMPTY=$(curl -s "http://localhost:8083/api/v1/channels/$CH_ID/search?q=nonexistent999&limit=10" -H "Authorization: Bearer $TOKEN")
check '"data":\[\]' "$SEARCH_EMPTY" "Search no results"

echo ""
echo "═══════════════════════════════════════════"
echo " Results: $PASS passed, $FAIL failed"
echo "═══════════════════════════════════════════"
[ $FAIL -eq 0 ] || exit 1
