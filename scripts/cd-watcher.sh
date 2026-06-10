#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# LOCAL CD WATCHER — Continuous Deployment to local system
#
# Polls GitHub every 30s for new commits on main.
# When detected: pulls → builds → restarts services → runs E2E.
# Zero clicks. Fully automated.
#
# Usage:
#   ./scripts/cd-watcher.sh              # foreground
#   ./scripts/cd-watcher.sh &            # background
#   nohup ./scripts/cd-watcher.sh &      # survives terminal close
#
# Logs: ./logs/cd-watcher.log
# Stop: kill $(cat .cd-watcher.pid)  OR  ./scripts/cd-stop.sh
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

# ═══ Logging ═══
log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    echo "$msg" | tee -a "$LOG_FILE"
}

# ═══ Cleanup on exit ═══
cleanup() {
    log "CD Watcher stopping..."
    rm -f "$PID_FILE" "$DEPLOY_LOCK"
    log "CD Watcher stopped."
    exit 0
}
trap cleanup SIGINT SIGTERM EXIT

# ═══ Check if already running ═══
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "CD Watcher already running (PID $OLD_PID). Stop with: kill $OLD_PID"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

echo $$ > "$PID_FILE"
cd "$ROOT_DIR"

log "═══════════════════════════════════════════════════"
log " CD WATCHER STARTED"
log " Branch: $BRANCH"
log " Poll interval: ${POLL_INTERVAL}s"
log " PID: $$"
log " Log: $LOG_FILE"
log "═══════════════════════════════════════════════════"

# ═══ Get current commit ═══
LAST_COMMIT=$(git rev-parse HEAD)
log "Current commit: ${LAST_COMMIT:0:8}"

# ═══ Deploy function ═══
deploy() {
    local NEW_COMMIT=$1

    # Prevent concurrent deploys
    if [ -f "$DEPLOY_LOCK" ]; then
        log "Deploy already in progress. Skipping."
        return
    fi
    touch "$DEPLOY_LOCK"

    log ""
    log "═══════════════════════════════════════════════════"
    log " NEW COMMIT DETECTED: ${NEW_COMMIT:0:8}"
    log " DEPLOYING..."
    log "═══════════════════════════════════════════════════"

    # Show what changed
    log "Changes:"
    git log --oneline "${LAST_COMMIT}..${NEW_COMMIT}" 2>&1 | while read line; do log "  $line"; done

    # Step 1: Pull
    log ""
    log ">>> Step 1/5: Pull"
    git pull origin "$BRANCH" --ff-only >> "$LOG_FILE" 2>&1
    if [ $? -ne 0 ]; then
        log "❌ Pull failed. Skipping deploy."
        rm -f "$DEPLOY_LOCK"
        return
    fi
    log "✅ Pull complete"

    # Step 2: Build
    log ""
    log ">>> Step 2/5: Build"
    mvn install -N -q >> "$LOG_FILE" 2>&1
    mvn install -pl common -q >> "$LOG_FILE" 2>&1
    mvn compile -q >> "$LOG_FILE" 2>&1
    if [ $? -ne 0 ]; then
        log "❌ Build failed. Skipping deploy."
        rm -f "$DEPLOY_LOCK"
        return
    fi
    log "✅ Build complete"

    # Step 3: Stop running services
    log ""
    log ">>> Step 3/5: Stop services"
    pkill -f "spring-boot:run" 2>/dev/null
    sleep 3
    log "✅ Services stopped"

    # Step 4: Reset DB + Start services
    log ""
    log ">>> Step 4/5: Deploy services"
    /opt/homebrew/opt/postgresql@14/bin/psql postgresql://slackuser:slackpass@localhost:5432/slackmsg \
        -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO slackuser;" >> "$LOG_FILE" 2>&1
    redis-cli FLUSHALL >> "$LOG_FILE" 2>&1

    for SVC in auth-service channel-service message-service media-service ws-gateway api-gateway; do
        mvn spring-boot:run -pl $SVC -q >> "$LOG_DIR/${SVC}.log" 2>&1 &
        sleep 8
    done
    sleep 5

    # Health check
    ALL_UP=true
    for PORT in 8080 8081 8082 8083 8084 8085; do
        STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','FAIL'))" 2>/dev/null || echo "DOWN")
        log "  :$PORT → $STATUS"
        [ "$STATUS" != "UP" ] && ALL_UP=false
    done

    if [ "$ALL_UP" = "false" ]; then
        log "❌ Not all services healthy. Deploy partially failed."
        rm -f "$DEPLOY_LOCK"
        return
    fi
    log "✅ All services UP"

    # Step 5: E2E test
    log ""
    log ">>> Step 5/5: E2E Tests"
    E2E_RESULT=$(./test-e2e.sh 2>&1)
    E2E_PASS=$(echo "$E2E_RESULT" | grep "Results:" | grep -o "[0-9]* passed")
    E2E_FAIL=$(echo "$E2E_RESULT" | grep "Results:" | grep -o "[0-9]* failed")

    echo "$E2E_RESULT" | grep -E "✅|❌" >> "$LOG_FILE"

    log ""
    log "═══════════════════════════════════════════════════"
    if echo "$E2E_FAIL" | grep -q "0 failed"; then
        log " ✅ DEPLOY SUCCESS: ${NEW_COMMIT:0:8}"
        log "    $E2E_PASS, $E2E_FAIL"
        log "    System live at http://localhost:8080"
    else
        log " ❌ DEPLOY FAILED E2E: ${NEW_COMMIT:0:8}"
        log "    $E2E_PASS, $E2E_FAIL"
        log "    Services running but E2E checks failed"
    fi
    log "═══════════════════════════════════════════════════"

    LAST_COMMIT="$NEW_COMMIT"
    rm -f "$DEPLOY_LOCK"
}

# ═══ Main loop ═══
log ""
log "Watching for changes on $BRANCH (every ${POLL_INTERVAL}s)..."
log "Press Ctrl+C to stop."
log ""

while true; do
    # Fetch latest from remote
    git fetch origin "$BRANCH" --quiet 2>/dev/null

    # Check if remote has new commits
    REMOTE_COMMIT=$(git rev-parse "origin/$BRANCH" 2>/dev/null)

    if [ "$REMOTE_COMMIT" != "$LAST_COMMIT" ] && [ -n "$REMOTE_COMMIT" ]; then
        deploy "$REMOTE_COMMIT"
    fi

    sleep "$POLL_INTERVAL"
done
