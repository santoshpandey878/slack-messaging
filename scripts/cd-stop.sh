#!/bin/bash
# Stop the CD watcher and all services
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ -f "$ROOT_DIR/.cd-watcher.pid" ]; then
    PID=$(cat "$ROOT_DIR/.cd-watcher.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "CD Watcher stopped (PID $PID)"
    else
        echo "CD Watcher not running (stale PID)"
    fi
    rm -f "$ROOT_DIR/.cd-watcher.pid"
else
    echo "CD Watcher not running"
fi

pkill -f "spring-boot:run" 2>/dev/null
echo "All services stopped"
