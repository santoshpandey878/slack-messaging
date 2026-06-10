#!/bin/bash
echo "Stopping all services..."
pkill -f "spring-boot:run" 2>/dev/null
pkill -f "auth-service" 2>/dev/null
pkill -f "channel-service" 2>/dev/null
pkill -f "message-service" 2>/dev/null
pkill -f "media-service" 2>/dev/null
pkill -f "ws-gateway" 2>/dev/null
echo "All stopped"
