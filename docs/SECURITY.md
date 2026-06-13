# Security

## Authentication

### JWT Token
- **Algorithm:** HS256 (symmetric)
- **Expiry:** 15 minutes (configurable via `jwt.expirationMs`)
- **Claims:** `sub` (userId), `tid` (tenantId), `role`, `name` (displayName)
- **Header:** `Authorization: Bearer <token>`

### Public Paths (no auth required)
Configured in `JwtAuthFilter.java`:
- `/api/v1/auth/register`
- `/api/v1/auth/login`
- `/health`
- `/actuator/*`
- `/internal/*` (inter-service — **Docker-network only, see note below**)
- `/ws` (WebSocket — auth via query param token)
- `/swagger-ui/*`, `/v3/api-docs`

To add new public paths, update `JwtAuthFilter.shouldNotFilter()`.

### WebSocket Auth
Token passed as query parameter: `ws://host/ws?token=<JWT>`. Validated in `WsHandler.afterConnectionEstablished()`.

## Authorization

### Tenant Isolation
- Every request sets `TenantContext` (ThreadLocal) from JWT
- All DB queries filter by `tenant_id`
- Cross-tenant access is impossible at the data layer

### Role-Based Access
- **admin** — can create users, manage channels, remove members
- **member** — can create channels, send messages, manage own membership
- Checked via `TenantContext.getUserRole()` or `AuthorizationService`

### Internal Endpoints (`/internal/*`) — Network Isolation
- Internal endpoints have **zero authentication** — no JWT validation
- They are ONLY accessible within the Docker network (service-to-service)
- The API Gateway (`ServiceRoutes.java`) does NOT route `/internal/*` paths — they are unreachable from outside
- **NEVER add `/internal/*` paths to `ServiceRoutes`** — this would expose them publicly
- **NEVER add business logic that relies on user identity** to internal endpoints — there is no `TenantContext` set
- If you need to pass tenant/user context, use query parameters (e.g., `?tenantId=...`)

### Channel Authorization
- `AuthorizationService.requireMembership(channelId)` — must be channel member
- `AuthorizationService.requireChannelAdminOrTenantAdmin(channelId)` — admin operations

## Password Security

- **BCrypt** with 12 rounds (`PasswordService.java`)
- **Login lockout:** 5 failed attempts in 15 minutes → account locked (`LoginGuardService.java`)
- Attempts tracked in Redis: `login:attempts:{slug}:{email}`

## Rate Limiting

Configured in `RateLimitFilter.java`:
- **Per-tenant:** 100 requests/second (configurable)
- **Per-user:** 10 requests/second (configurable)
- Redis sliding window (1-second buckets)
- Returns 429 Too Many Requests when exceeded

## Input Validation

- **Request body:** `@Valid` + Bean Validation annotations
- **Content type whitelist:** Media service validates: images, videos, audio, PDF, text
- **Filename sanitization:** Path traversal prevention in media uploads
- **Message size:** Max 40KB (configurable)
- **XSS prevention:** HTML demo uses `textContent` and `escapeHtml()`, never `innerHTML`

## Secrets

- JWT secret: `JWT_SECRET` env var (Docker Compose default for local dev)
- DB password: `DB_PASSWORD` env var
- MinIO credentials: `STORAGE_ACCESS_KEY`/`STORAGE_SECRET_KEY` env vars
- All configurable via environment — no hardcoded secrets in code
