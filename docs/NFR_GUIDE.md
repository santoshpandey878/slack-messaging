# Non-Functional Requirements Guide

When Harinder asks for NFR improvements (performance, monitoring, rate limiting, scalability), this document tells the agent exactly what to do. Every NFR has a concrete implementation path.

---

## 1. Performance / Latency

### Current State
- Message send: ~15-25ms P99 (DB write + sync fan-out)
- History fetch: ~5-10ms (indexed query, cursor pagination)
- WebSocket delivery: ~20-50ms P99 (Redis Pub/Sub → WS)

### If Asked: "Reduce message send latency"
1. **Async fan-out via background thread** (simplest):
   ```java
   // In MessageService, replace direct fanoutService.fanout() with:
   @Async
   public void triggerFanoutAsync(UUID tenantId, UUID channelId, Message msg, UUID senderId, String senderName) {
       fanoutService.fanout(tenantId, channelId, msg, senderId, senderName);
   }
   ```
   - Add `@EnableAsync` to the service's config class
   - Message send returns immediately after DB write (~5-10ms)
   - Fan-out happens in background thread

2. **Connection pooling for inter-service calls:**
   - Already using Spring's `RestTemplate` (connection pooled by default)
   - Tune in `application.yml`: `spring.datasource.hikari.maximum-pool-size: 20`

3. **Redis pipeline for batch unread:**
   - Current: individual HINCRBY per offline member
   - Improvement: use Redis PIPELINE to batch all unread increments

### If Asked: "Handle high throughput (10K+ messages/sec)"
- **Current limit:** ~200 msg/sec per service instance (sync fan-out)
- **Scale path:** Already documented in ARCHITECTURE.md:
  - Swap `FanoutService` to Kafka consumer (async, same interface via PubSubService port)
  - Swap `MessageStore` to Cassandra adapter (write-optimized, same interface)
  - Add more ws-gateway instances (horizontal, server-deduped Pub/Sub already handles this)

---

## 2. Monitoring / Observability

### Current State
- Spring Actuator: `/actuator/health`, `/actuator/metrics` on all services
- MDC structured logging: `tenantId`, `userId` on every request
- Activity log in HTML demo client

### If Asked: "Add distributed tracing"

**Current:** MDC structured logging with `tenantId` and `userId` on every request. Cross-service calls are traceable via log correlation, but no automated span propagation.

**Scale path (OpenTelemetry + Jaeger):**
1. Add `opentelemetry-javaagent` as a JVM argument in each Dockerfile:
   ```dockerfile
   ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]
   ```
2. Configure exporter in `application.yml`:
   ```yaml
   otel:
     exporter:
       otlp:
         endpoint: http://jaeger:4317
     service:
       name: ${spring.application.name}
   ```
3. Add Jaeger to `docker-compose.yml`:
   ```yaml
   jaeger:
     image: jaegertracing/all-in-one:latest
     ports: ["16686:16686", "4317:4317"]
   ```
4. Traces auto-propagate across REST calls (RestTemplate) and Redis operations
5. View at `http://localhost:16686` — full request waterfall across all 6 services

**Why not implemented now:** Adds infra complexity. MDC logging is sufficient for debugging. Tracing is a "when you need it" addition, not a blocker.

### If Asked: "Add monitoring / metrics"

**Endpoint-level metrics (Micrometer — already included via Actuator):**
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
```
- Actuator auto-exposes: request count, latency percentiles, error rates, JVM metrics
- Access at: `http://localhost:{port}/actuator/metrics`

**Custom business metrics:**
```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MeterRegistry meterRegistry;

    public MessageResponse sendMessage(...) {
        // ... business logic ...
        meterRegistry.counter("messages.sent",
            "tenant", tenantId.toString(),
            "type", msgType.name()
        ).increment();
    }
}
```

**If Prometheus/Grafana needed:**
1. Add to `pom.xml`: `micrometer-registry-prometheus`
2. Add Prometheus + Grafana containers to `docker-compose.yml`
3. Configure Prometheus to scrape all service `/actuator/prometheus` endpoints

**Health check enhancements:**
```java
@Component
public class RedisHealthIndicator implements HealthIndicator {
    private final CacheService cache;
    @Override
    public Health health() {
        try {
            cache.set("health:check", "ok", Duration.ofSeconds(5));
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
```

---

## 3. Rate Limiting

### Current State
- Per-tenant: 100 req/sec (configurable via `app.rateLimit.tenantPerSecond`)
- Per-user: 10 req/sec (configurable via `app.rateLimit.userPerSecond`)
- Redis sliding window in `RateLimitFilter`

### If Asked: "Add per-endpoint rate limits"

```java
// In RateLimitFilter, add endpoint-specific limits:
private int getEndpointLimit(String path) {
    if (path.contains("/messages") && "POST".equals(method)) return 5;  // 5 messages/sec
    if (path.contains("/reactions")) return 10;  // 10 reactions/sec
    if (path.contains("/search")) return 2;  // 2 searches/sec
    return appConfig.getRateLimit().getUserPerSecond();  // default
}
```

### If Asked: "Add rate limit headers"
```java
// In RateLimitFilter, after checking:
response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));
response.setHeader("X-RateLimit-Reset", String.valueOf(window + 1));  // next window
```

---

## 4. Data Retention / Cleanup

### If Asked: "Add message retention policy"

**Per-tenant retention:**
```sql
-- Add to tenants table
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS message_retention_days INT DEFAULT 365;
```

**Cleanup job:**
```java
@Service
@RequiredArgsConstructor
public class RetentionService {
    private final MessageRepository messageRepo;

    @Scheduled(cron = "0 0 3 * * *")  // 3 AM daily
    @Transactional
    public void cleanupExpiredMessages() {
        // Delete soft-deleted messages older than 30 days (hard delete)
        messageRepo.deleteHardByIsDeletedTrueAndCreatedAtBefore(
            Instant.now().minus(30, ChronoUnit.DAYS));

        // Soft-delete messages past retention period per tenant
        // (query tenants for retention_days, delete old messages)
    }
}
```

### If Asked: "Add media cleanup"
- Background job scans S3/MinIO for keys not referenced in any message
- Run weekly, delete orphaned files older than 7 days

---

## 5. Connection Limits / Backpressure

### If Asked: "Handle connection overload"

**WebSocket connection limits:**
```java
// In WsHandler.afterConnectionEstablished():
if (sessionManager.getActiveCount() >= MAX_CONNECTIONS_PER_INSTANCE) {
    session.close(CloseStatus.SERVICE_OVERLOAD.withReason("Too many connections"));
    return;
}
```

**Per-tenant connection limits:**
```java
long tenantConnections = sessionManager.getAllSessions().entrySet().stream()
    .filter(e -> e.getKey().startsWith(tenantId + ":"))
    .count();
if (tenantConnections >= MAX_CONNECTIONS_PER_TENANT) {
    session.close(CloseStatus.POLICY_VIOLATION.withReason("Tenant connection limit reached"));
    return;
}
```

**Database connection pool:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20          # max connections per service
      minimum-idle: 5                 # min idle connections
      connection-timeout: 5000        # 5s timeout to get connection
      idle-timeout: 300000            # 5 min idle before close
      max-lifetime: 600000            # 10 min max connection lifetime
```

---

## 6. Caching

### If Asked: "Add caching for frequently accessed data"

**Channel membership cache (most impactful):**
```java
@Service
public class CachedMembershipService {
    private final ChannelServicePort channelService;
    private final CacheService cache;

    public boolean isMemberCached(UUID channelId, UUID userId) {
        String key = "member:" + channelId + ":" + userId;
        String cached = cache.get(key);
        if (cached != null) return "1".equals(cached);

        boolean isMember = channelService.isMember(channelId, userId);
        cache.set(key, isMember ? "1" : "0", Duration.ofSeconds(60));
        return isMember;
    }

    // Invalidate on member add/remove
    public void invalidate(UUID channelId, UUID userId) {
        cache.del("member:" + channelId + ":" + userId);
    }
}
```

**CAUTION:** Only cache for WS delivery checks (best-effort). Never cache for message-service membership check (security-critical — always hit DB per TRADEOFFS.md).

---

## 7. Bulk Operations

### If Asked: "Support bulk message delete / bulk reactions"

**Pattern: Batch endpoint with limit:**
```java
@PostMapping("/bulk-delete")
public ResponseEntity<ApiResponse<Map<String, Integer>>> bulkDelete(
        @PathVariable UUID channelId,
        @Valid @RequestBody BulkDeleteRequest request) {
    // Max 100 items per batch
    if (request.getMessageIds().size() > 100) {
        throw new IllegalArgumentException("Maximum 100 messages per batch");
    }
    int deleted = messageService.bulkDelete(channelId, request.getMessageIds());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", deleted)));
}
```

**Rules for bulk operations:**
- Always cap batch size (100 max)
- Use single transaction for consistency
- Fan out ONE event with all IDs (not one event per item)
- Log total count, not individual items

---

## 8. Security Hardening

### If Asked: "Add CORS / CSP / security headers"

```java
@Configuration
public class SecurityHeadersConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("X-XSS-Protection", "1; mode=block");
                res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                return true;
            }
        });
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:8080")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

### If Asked: "Add JWT refresh tokens"
1. Add `POST /api/v1/auth/refresh` endpoint
2. Issue short-lived access token (15 min) + long-lived refresh token (7 days)
3. Store refresh token hash in Redis (revocable)
4. Client calls refresh before access token expires

### If Asked: "Add audit logging"
```java
@Component
@Slf4j
public class AuditService {
    public void log(String action, UUID userId, UUID tenantId, Map<String, Object> details) {
        log.info("AUDIT action={} userId={} tenantId={} details={}",
                action, userId, tenantId, details);
        // Future: persist to audit_log table
    }
}
```

---

## 9. Scalability Readiness

### Already Built (via Hexagonal Architecture)
| Component | Current | Scale Swap | Effort |
|-----------|---------|-----------|--------|
| MessageStore | PostgreSQL | Cassandra adapter | New adapter class, same interface |
| PubSubService | Redis Pub/Sub | Kafka adapter | New adapter class, same interface |
| CacheService | Redis | Redis Cluster | Config change only |
| ObjectStorageService | MinIO | S3 | Config change only |
| ChannelServicePort | REST | gRPC adapter | New adapter class, same interface |
| Fan-out | Sync (in-process) | Async (Kafka consumer) | New consumer, same FanoutService |

### If Asked: "Make it handle 1000 tenants"
- Already multi-tenant with `tenant_id` everywhere
- Add per-tenant rate limiting (already built)
- Add tenant-level quotas (max_users, max_channels already in tenant table)
- Add tenant isolation in Redis keys (already namespaced)

### If Asked: "Add horizontal scaling"
- All services are stateless (session state in Redis, not in-memory)
- WS gateway uses server-deduped Pub/Sub (already multi-instance ready)
- Docker Compose: just increase `replicas` per service
- API Gateway: round-robin to multiple backend instances

---

## 10. Testing NFRs

### If Asked: "Add load testing"

**k6 load test script:**
```javascript
// k6-test.js
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';

export let options = {
    stages: [
        { duration: '30s', target: 50 },   // ramp up
        { duration: '1m', target: 100 },    // sustained
        { duration: '30s', target: 0 },     // ramp down
    ],
};

export default function() {
    // Register + login
    const res = http.post('http://localhost:8080/api/v1/auth/login', JSON.stringify({
        tenantSlug: 'loadtest', email: 'user@test.com', password: 'pass123'
    }), { headers: { 'Content-Type': 'application/json' } });

    check(res, { 'login ok': (r) => r.status === 200 });
    const token = JSON.parse(res.body).data.token;

    // Send message
    const msg = http.post('http://localhost:8080/api/v1/channels/' + CHANNEL_ID + '/messages',
        JSON.stringify({ content: 'Load test message ' + __ITER }),
        { headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } });

    check(msg, { 'msg sent': (r) => r.status === 200 });
    sleep(1);
}
```

Run: `k6 run k6-test.js`
