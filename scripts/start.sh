#!/bin/bash
# Start all services (infra must be running: docker-compose up -d)
set -e
echo "Starting all services..."

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
MVN="/Users/santosh.pandey/apache-maven-3.8.6/bin/mvn"

# Build first
$MVN install -N -q
$MVN install -pl common -q
$MVN compile -q

# Start each service
for MODULE in auth-service channel-service message-service media-service ws-gateway api-gateway; do
  echo "  Starting $MODULE..."
  $MVN spring-boot:run -pl $MODULE -q &
  sleep 8
done

echo ""
echo "═══════════════════════════════════════"
echo " All services running!"
echo "═══════════════════════════════════════"
echo "  API Gateway:    http://localhost:8080  ← USE THIS"
echo "  auth-service:   http://localhost:8081"
echo "  channel-service:http://localhost:8082"
echo "  message-service:http://localhost:8083"
echo "  media-service:  http://localhost:8084"
echo "  ws-gateway:     ws://localhost:8085"
echo ""
echo "  Demo UI: http://localhost:8080"
echo "  Swagger: http://localhost:8081/swagger-ui.html (auth)"
echo ""
echo "Press Ctrl+C to stop all"
wait
