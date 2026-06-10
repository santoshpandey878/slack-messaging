#!/bin/bash
# Run unit tests for all service modules
set -e
echo "Running unit tests..."
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test -pl common,auth-service,channel-service,message-service,media-service,ws-gateway 2>&1 | grep -E "Tests run|BUILD"
echo ""
echo "✅ Unit tests complete"
