#!/bin/bash
# Stop CD watcher + all Docker services
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ -f "$ROOT_DIR/.cd-watcher.pid" ]; then
    PID=$(cat "$ROOT_DIR/.cd-watcher.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "CD Watcher stopped (PID $PID)"
    else
        echo "CD Watcher not running (stale PID)"
    fi
    rm -f "$ROOT_DIR/.cd-watcher.pid" "$ROOT_DIR/.cd-deploying"
else
    echo "CD Watcher not running"
fi

# Stop Docker services (keep infra running)
echo "Stopping application containers..."
cd "$ROOT_DIR"
docker-compose stop auth-service channel-service message-service media-service ws-gateway api-gateway 2>/dev/null
echo "Done. Infra (PG, Redis, MinIO) still running."
echo ""
echo "To stop everything: docker-compose down"
echo "To stop + delete data: docker-compose down -v"
