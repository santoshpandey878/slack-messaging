#!/bin/bash
# Build JARs + Docker images + deploy all services
set -e
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME=$(/usr/libexec/java_home -v 11)

echo "═══ BUILD + DEPLOY ═══"

echo ">>> Ensuring Colima certs..."
"$ROOT_DIR/scripts/fix-colima-certs.sh" 2>/dev/null || true
echo "✅ Certs OK"

echo ">>> Building JARs..."
mvn install -N -q
mvn install -pl common -q
mvn package -DskipTests -q
echo "✅ JARs built"

echo ">>> Building Docker images..."
docker-compose build --quiet
echo "✅ Docker images built"

echo ">>> Starting all services..."
docker-compose up -d
echo "✅ Services starting"

echo ""
echo "Waiting for health checks (up to 60s)..."
for i in $(seq 1 6); do
  sleep 10
  ALL_UP=true
  for PORT in 8080 8081 8082 8083 8084 8085; do
    STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
    [ "$STATUS" != "UP" ] && ALL_UP=false
  done
  if [ "$ALL_UP" = "true" ]; then break; fi
  echo "  Attempt $i/6..."
done

echo ""
for PORT in 8080 8081 8082 8083 8084 8085; do
  STATUS=$(curl -s -m 3 "http://localhost:$PORT/actuator/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "DOWN")
  echo "  :$PORT → $STATUS"
done

echo ""
echo "═══ DEPLOYED ═══"
echo "  http://localhost:8080 — API Gateway + Demo UI"
echo "  docker-compose logs -f — view logs"
echo "  docker-compose down — stop all"
