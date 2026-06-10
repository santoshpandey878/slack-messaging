#!/bin/bash
# Full CI/CD pipeline: build → test → start → E2E → report
set -e

echo "═══════════════════════════════════════"
echo " PIPELINE: Build → Test → Deploy → E2E"
echo "═══════════════════════════════════════"

cd "$(dirname "$0")/.."

# 1. Build
echo ""; echo ">>> STEP 1: Build"
./scripts/build.sh

# 2. Unit Tests
echo ""; echo ">>> STEP 2: Unit Tests"
./scripts/test.sh

# 3. Stop any running services
echo ""; echo ">>> STEP 3: Deploy (start services)"
pkill -f "spring-boot:run" 2>/dev/null; sleep 2

# Reset DB
/opt/homebrew/opt/postgresql@14/bin/psql postgresql://slackuser:slackpass@localhost:5432/slackmsg \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO slackuser;" 2>&1 > /dev/null
redis-cli FLUSHALL 2>&1 > /dev/null

# Start services
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
for MODULE in auth-service channel-service message-service media-service ws-gateway api-gateway; do
  mvn spring-boot:run -pl $MODULE -q &
  sleep 8
done
sleep 5

# Health check
echo ""; echo ">>> STEP 4: Health Check"
ALL_UP=true
for PORT in 8080 8081 8082 8083 8084 8085; do
  STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','FAIL'))" 2>/dev/null || echo "DOWN")
  echo "  :$PORT → $STATUS"
  [ "$STATUS" != "UP" ] && ALL_UP=false
done

if [ "$ALL_UP" = false ]; then
  echo "❌ Not all services healthy. Aborting."
  pkill -f "spring-boot:run" 2>/dev/null
  exit 1
fi

# 4. E2E Tests
echo ""; echo ">>> STEP 5: E2E Tests"
./test-e2e.sh

# Done
echo ""
echo "═══════════════════════════════════════"
echo " ✅ PIPELINE COMPLETE — ALL CHECKS PASSED"
echo "═══════════════════════════════════════"
echo " Services running at http://localhost:8080"
echo " Stop with: ./scripts/stop.sh"
