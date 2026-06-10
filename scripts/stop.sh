#!/bin/bash
# Stop all services
echo "Stopping all services..."
pkill -f "spring-boot:run" 2>/dev/null
sleep 2
echo "✅ All stopped"
