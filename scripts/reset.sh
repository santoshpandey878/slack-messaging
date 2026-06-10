#!/bin/bash
# Full reset: stop services, clean DB, clean Redis, restart
set -e
echo "Full reset..."

# Stop services
pkill -f "spring-boot:run" 2>/dev/null
sleep 2

# Reset Docker infra
docker-compose down -v 2>/dev/null
docker-compose up -d
sleep 5

echo "✅ Infrastructure reset. Start services with: ./scripts/start.sh"
