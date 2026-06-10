# Deployment

## Local Development (Docker)

All 9 containers (3 infra + 6 services) run via Docker Compose.

### Start Everything
```bash
# Full deploy (build + start + health check)
./scripts/deploy.sh

# Or manually:
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn install -N -q && mvn install -pl common -q && mvn package -DskipTests -q
docker-compose build --quiet
docker-compose up -d
```

### Stop
```bash
docker-compose stop                    # stop services, keep data
docker-compose down                    # stop + remove containers
docker-compose down -v                 # stop + remove containers + volumes (full reset)
```

### View Logs
```bash
docker-compose logs -f                 # all services
docker-compose logs -f message-service # single service
```

### Ports
| Service | Port | URL |
|---------|------|-----|
| API Gateway | 8080 | http://localhost:8080 |
| Auth | 8081 | http://localhost:8081 |
| Channel | 8082 | http://localhost:8082 |
| Message | 8083 | http://localhost:8083 |
| Media | 8084 | http://localhost:8084 |
| WS Gateway | 8085 | ws://localhost:8080/ws |
| PostgreSQL | 5432 | |
| Redis | 6379 | |
| MinIO | 9000/9001 | http://localhost:9001 (console) |

## Colima (Docker on macOS)

```bash
colima start --cpu 4 --memory 4
```

**TLS certificate fix** (corporate proxy):
```bash
./scripts/fix-colima-certs.sh
```
Must re-run after `colima delete`. Not needed after `colima stop/start`.

## CI/CD

### CI (GitHub Actions)
**File:** `.github/workflows/ci.yml`

Runs on every push/PR:
1. Start PostgreSQL + Redis services
2. Build all modules
3. Run tests
4. Run E2E tests

### CD (Local Watcher)
**File:** `scripts/cd-watcher.sh`

Polls GitHub every 30s. On new commits:
1. `git pull`
2. `mvn package`
3. `docker-compose build && up`
4. Health check
5. E2E tests

```bash
./scripts/cd-watcher.sh &     # start in background
./scripts/cd-stop.sh           # stop
```

## Dockerfiles

All services use the same Dockerfile pattern:
```dockerfile
FROM amazoncorretto:11-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Environment Variables

| Variable | Service | Default |
|----------|---------|---------|
| DB_URL | all | jdbc:postgresql://postgres:5432/slackmsg |
| DB_USERNAME | all | slackuser |
| DB_PASSWORD | all | slackpass |
| REDIS_HOST | all | redis |
| JWT_SECRET | auth, channel, message, media, ws | (set in docker-compose) |
| CHANNEL_SERVICE_URL | message, ws | http://channel-service:8082 |
| MESSAGE_SERVICE_URL | ws | http://message-service:8083 |
| AUTH_SERVICE_URL | gateway | http://auth-service:8081 |
| MEDIA_SERVICE_URL | gateway | http://media-service:8084 |
| WS_GATEWAY_URL | gateway | http://ws-gateway:8085 |
| STORAGE_ENDPOINT | media | http://minio:9000 |
| STORAGE_ACCESS_KEY | media | minioadmin |
| STORAGE_SECRET_KEY | media | minioadmin |

## Database Access

```bash
/opt/homebrew/opt/postgresql@14/bin/psql postgresql://slackuser:slackpass@localhost:5432/slackmsg
```

## Health Checks

All services expose Spring Actuator:
```bash
curl http://localhost:8081/actuator/health
# {"status":"UP"}
```
