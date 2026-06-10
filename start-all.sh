#!/bin/bash
# Start all services in background
echo "Starting all services..."

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
MVN="/Users/santosh.pandey/apache-maven-3.8.6/bin/mvn"

# Build first
$MVN compile -q || { echo "Build failed"; exit 1; }

# Start each service in background
for MODULE in auth-service channel-service message-service media-service ws-gateway api-gateway; do
  echo "Starting $MODULE..."
  $MVN spring-boot:run -pl $MODULE -q &
  sleep 3
done

echo ""
echo "All services starting..."
echo "  auth-service:     http://localhost:8081"
echo "  channel-service:  http://localhost:8082"
echo "  message-service:  http://localhost:8083"
echo "  media-service:    http://localhost:8084"
echo "  ws-gateway:       ws://localhost:8085"
echo ""
echo "Press Ctrl+C to stop all"
wait
