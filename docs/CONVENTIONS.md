# Coding Conventions

## Technology Constraints

- **Java 11** — no `var` in lambdas, no records, no sealed classes, no text blocks
- **Spring Boot 2.7.18** — `javax.persistence.*`, `javax.validation.*` (NOT `jakarta.*`)
- **Lombok** — `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder @RequiredArgsConstructor @Slf4j @Data`
- **Maven** — always rebuild common first: `mvn install -N -q && mvn install -pl common -q`

## Package Structure Per Service

```
{service}/src/main/java/com/slackmsg/{service}/
├── handler/              ← REST controllers (@RestController)
├── service/              ← Business logic (@Service)
├── adapter/
│   └── postgres/         ← JPA repositories + port implementations
├── client/               ← REST clients for inter-service calls
└── config/               ← Spring configuration classes
```

## Naming

| Item | Pattern | Example |
|------|---------|---------|
| Entity | Singular noun | `Reaction`, `PinnedMessage` |
| Repository | `{Entity}Repository` | `ReactionRepository` |
| Service | `{Feature}Service` | `ReactionService`, `BookmarkService` |
| Handler | `{Feature}Handler` | `ReactionHandler` |
| Request DTO | `{Action}{Feature}Request` | `AddReactionRequest` |
| Response DTO | `{Feature}Response` | `ReactionResponse` |
| Internal handler | `{Service}InternalHandler` | `MessageInternalHandler` |
| Client | `{Service}ServiceClient` | `ChannelServiceClient` |
| Port interface | `{Feature}Store` or `{Feature}ServicePort` | `MessageStore`, `ChannelServicePort` |

## Service Rules

1. **Single Responsibility** — each service class does ONE thing, under 100 lines
2. **Constructor injection** — `@RequiredArgsConstructor` + `private final` fields
3. **No `@Autowired`** — always constructor injection via Lombok
4. **Use TenantContext** — never pass tenantId/userId as handler parameters
5. **Throw exceptions** — `IllegalArgumentException` (400), `SecurityException` (403), let `GlobalExceptionHandler` handle
6. **Logging** — `@Slf4j`, use `log.debug()` for normal flow, `log.error()` for failures

## Handler Rules

1. **Thin** — extract params, call service, return `ApiResponse`. NO business logic.
2. **Always `@Valid`** on `@RequestBody`
3. **Always return** `ResponseEntity<ApiResponse<T>>`
4. **Path variables** as method params with `@PathVariable`

## Entity Rules

1. All entities in `common/src/main/java/com/slackmsg/domain/entity/`
2. Always include `tenant_id` column
3. Use `@Builder.Default` for fields with defaults
4. UUID primary key with `@GenericGenerator(strategy = "org.hibernate.id.UUIDGenerator")`
5. Timestamps as `Instant` with `@Column(name = "created_at")`
6. Use `javax.persistence.*` imports

## DTO Rules

1. Request DTOs: `@Data` + validation annotations (`@NotBlank`, `@NotNull`, `@Size`, etc.)
2. Response DTOs: `@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)` + static `from(Entity)` method
3. All DTOs in `common/` module (shared across services)

## Redis Key Rules

All Redis keys defined in `common/src/main/java/com/slackmsg/util/RedisKeys.java`. Never hardcode key patterns in services.

## Error Handling

- `IllegalArgumentException` → 400 Bad Request
- `SecurityException` → 403 Forbidden
- `DataAccessException` → 500 (generic message, real error logged)
- All others → 500 (generic message)
- Never expose stack traces to clients

## File Locations Quick Reference

| What | Where |
|------|-------|
| Entities | `common/src/main/java/com/slackmsg/domain/entity/` |
| Enums | `common/src/main/java/com/slackmsg/domain/enums/` |
| Request DTOs | `common/src/main/java/com/slackmsg/dto/request/` |
| Response DTOs | `common/src/main/java/com/slackmsg/dto/response/` |
| Port interfaces | `common/src/main/java/com/slackmsg/port/` |
| Redis adapters | `common/src/main/java/com/slackmsg/adapter/redis/` |
| Middleware | `common/src/main/java/com/slackmsg/middleware/` |
| Utilities | `common/src/main/java/com/slackmsg/util/` |
| Migrations | `{service}/src/main/resources/db/migration/` |
| App config | `{service}/src/main/resources/application.yml` |
