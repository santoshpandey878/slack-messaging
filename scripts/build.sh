#!/bin/bash
# Build all modules
set -e
echo "Building all modules..."
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -N -q
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -pl common -q
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn compile -q
echo "✅ Build complete"
