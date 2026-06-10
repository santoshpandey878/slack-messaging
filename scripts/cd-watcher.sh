#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# LOCAL CD WATCHER — Continuous Deployment via Docker
#
# Polls GitHub every 30s for new commits on main.
# On detection: pull → build JARs → docker-compose build → docker-compose up
# Zero clicks. Fully automated. Production-like deployment.
#
# Usage:
#   ./scripts/cd-watcher.sh              # foreground
#   ./scripts/cd-watcher.sh &            # background
#
# Stop: ./scripts/cd-stop.sh
# ═══════════════════════════════════════════════════════════════

set -o pipefail
POLL_INTERVAL=${CD_POLL_INTERVAL:-30}
BRANCH="main"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
LOG_FILE="$LOG_DIR/cd-watcher.log"
PID_FILE="$ROOT_DIR/.cd-watcher.pid"
DEPLOY_LOCK="$ROOT_DIR/.cd-deploying"

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
export PATH="/Users/santosh.pandey/apache-maven-3.8.6/bin:$PATH"

mkdir -p "$LOG_DIR"

log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    echo "$msg" | tee -a "$LOG_FILE"
}

cleanup() {
    log "CD Watcher stopping..."
    rm -f "$PID_FILE" "$DEPLOY_LOCK"
    log "CD Watcher stopped."
    exit 0
}
trap cleanup SIGINT SIGTERM EXIT

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "CD Watcher already running (PID $OLD_PID). Stop: ./scripts/cd-stop.sh"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

echo $$ > "$PID_FILE"
cd "$ROOT_DIR"

log "═══════════════════════════════════════════════════"
log " CD WATCHER STARTED (Docker mode)"
log " Branch: $BRANCH | Poll: ${POLL_INTERVAL}s | PID: $$"
log "═══════════════════════════════════════════════════"

LAST_COMMIT=$(git rev-parse HEAD)
log "Current commit: ${LAST_COMMIT:0:8}"

deploy() {
    local NEW_COMMIT=$1

    if [ -f "$DEPLOY_LOCK" ]; then
        log "Deploy in progress. Skipping."
        return
    fi
    touch "$DEPLOY_LOCK"

    log ""
    log "═══════════════════════════════════════════════════"
    log " NEW COMMIT: ${NEW_COMMIT:0:8} — DEPLOYING..."
    log "═══════════════════════════════════════════════════"
    git log --oneline "${LAST_COMMIT}..${NEW_COMMIT}" 2>&1 | while read line; do log "  $line"; done

    # Step 1: Pull
    log ">>> Step 1/4: Pull"
    git pull origin "$BRANCH" --ff-only >> "$LOG_FILE" 2>&1
    if [ $? -ne 0 ]; then log "❌ Pull failed"; rm -f "$DEPLOY_LOCK"; return; fi
    log "✅ Pull"

    # Step 2: Build JARs
    log ">>> Step 2/4: Build JARs"
    mvn install -N -q >> "$LOG_FILE" 2>&1
    mvn install -pl common -q >> "$LOG_FILE" 2>&1
    mvn package -DskipTests -q >> "$LOG_FILE" 2>&1
    if [ $? -ne 0 ]; then log "❌ Build failed"; rm -f "$DEPLOY_LOCK"; return; fi
    log "✅ Build"

    # Step 3: Docker build + deploy
    log ">>> Step 3/4: Docker build + deploy"
    docker-compose build --quiet >> "$LOG_FILE" 2>&1
    docker-compose up -d >> "$LOG_FILE" 2>&1
    if [ $? -ne 0 ]; then log "❌ Docker deploy failed"; rm -f "$DEPLOY_LOCK"; return; fi

    # Wait for health
    log "Waiting for services to be healthy..."
    sleep 30
    ALL_UP=true
    for PORT in 8080 8081 8082 8083 8084 8085; do
        STATUS=$(curl -s -m 5 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','FAIL'))" 2>/dev/null || echo "DOWN")
        log "  :$PORT → $STATUS"
        [ "$STATUS" != "UP" ] && ALL_UP=false
    done

    if [ "$ALL_UP" = "false" ]; then
        log "⚠️ Not all services UP. Waiting 30s more..."
        sleep 30
        # Retry health check
        for PORT in 8080 8081 8082 8083 8084 8085; do
            STATUS=$(curl -s -m 5 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','FAIL'))" 2>/dev/null || echo "DOWN")
            [ "$STATUS" != "UP" ] && log "  :$PORT still DOWN"
        done
    fi
    log "✅ Services deployed"

    # Step 4: E2E test
    log ">>> Step 4/4: E2E Tests"
    E2E_RESULT=$(./test-e2e.sh 2>&1)
    echo "$E2E_RESULT" | grep -E "✅|❌" >> "$LOG_FILE"
    E2E_PASS=$(echo "$E2E_RESULT" | grep "Results:" | grep -o "[0-9]* passed")
    E2E_FAIL=$(echo "$E2E_RESULT" | grep "Results:" | grep -o "[0-9]* failed")

    log ""
    log "═══════════════════════════════════════════════════"
    if echo "$E2E_FAIL" | grep -q "0 failed"; then
        log " ✅ DEPLOY SUCCESS: ${NEW_COMMIT:0:8}"
        log "    $E2E_PASS, $E2E_FAIL"
        log "    System live at http://localhost:8080"
    else
        log " ❌ DEPLOY FAILED E2E: ${NEW_COMMIT:0:8}"
        log "    $E2E_PASS, $E2E_FAIL"
    fi
    log "═══════════════════════════════════════════════════"

    LAST_COMMIT="$NEW_COMMIT"
    rm -f "$DEPLOY_LOCK"
}

log "Watching for changes..."

while true; do
    git fetch origin "$BRANCH" --quiet 2>/dev/null
    REMOTE_COMMIT=$(git rev-parse "origin/$BRANCH" 2>/dev/null)

    if [ "$REMOTE_COMMIT" != "$LAST_COMMIT" ] && [ -n "$REMOTE_COMMIT" ]; then
        deploy "$REMOTE_COMMIT"
    fi

    sleep "$POLL_INTERVAL"
done
