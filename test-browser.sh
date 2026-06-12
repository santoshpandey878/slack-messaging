#!/bin/bash
# ═══════════════════════════════════════════════════
# Browser Tests — Multi-user Playwright tests via Docker
#
# Runs real Chromium browser tests to catch WS/UI bugs
# that curl-based E2E tests cannot detect.
#
# Prerequisites: all services running, Docker available
# Usage: ./test-browser.sh
# ═══════════════════════════════════════════════════
set -e

echo "═══ BROWSER TESTS (Playwright in Docker) ═══"
echo ""

# Check services are up
for PORT in 8080 8081 8082 8083; do
  STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
  if [ "$STATUS" != "UP" ]; then
    echo "ERROR: Service on port $PORT is $STATUS. Start all services first."
    exit 1
  fi
done

echo "Building browser test image..."
docker build -t slack-browser-tests tests/browser/ --quiet

echo "Running browser tests..."
echo ""
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  slack-browser-tests "$@"

echo ""
echo "═══ Browser tests complete ═══"
