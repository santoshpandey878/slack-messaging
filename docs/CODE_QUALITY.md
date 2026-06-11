# Code Quality Standards

The agent MUST follow every rule in this document. No exceptions. These prevent bugs, security issues, and consistency problems.

---

## 1. Method Rules

- **Max 20 lines per method.** If longer, extract a private helper.
- **Max 100 lines per class.** If longer, split into focused classes (SRP).
- **One public method per concern.** A service class does ONE thing.
- **No nested if-else deeper than 2 levels.** Use early returns / guard clauses.

```java
// BAD — nested
if (user != null) {
    if (user.getStatus().equals("active")) {
        if (channelStore.isMember(channelId, userId)) {
            // do something
        }
    }
}

// GOOD — guard clauses
if (user == null) throw new IllegalArgumentException("User not found");
if (!"active".equals(user.getStatus())) throw new IllegalArgumentException("User deactivated");
if (!channelStore.isMember(channelId, userId)) throw new SecurityException("Not a member");
// do something
```

## 2. Null Safety

- **Never return null from a public method.** Return `Optional<T>`, empty list, or throw.
- **Never pass null as a method argument.** Use overloads or builder pattern.
- **Always null-check external inputs:** request body fields, path variables, JWT claims.
- **Use `Objects.requireNonNull()`** for constructor-injected dependencies that must not be null.

```java
// When accessing nullable fields from entities
String senderName = message.getSenderName() != null ? message.getSenderName() : "Unknown";

// When parsing UUIDs from strings
try {
    UUID id = UUID.fromString(idString);
} catch (IllegalArgumentException e) {
    throw new IllegalArgumentException("Invalid ID format");
}
```

## 3. Exception Rules

| When | Throw | HTTP Code |
|------|-------|-----------|
| Invalid input (empty name, bad format) | `IllegalArgumentException` | 400 |
| Resource not found | `IllegalArgumentException` | 400 |
| Not authenticated | Handled by `JwtAuthFilter` | 401 |
| Not authorized (not member, not admin) | `SecurityException` | 403 |
| Duplicate (unique constraint hit) | Catch `DataIntegrityViolationException` → `IllegalArgumentException` | 400 or 409 |
| Unexpected error | Let it propagate → `GlobalExceptionHandler` | 500 |

### Rules
- **Never catch `Exception` broadly in services.** Let `GlobalExceptionHandler` handle it.
- **One exception:** Fan-out. Wrap in try-catch because fan-out failure must not fail the write.
- **Never throw checked exceptions.** Use `RuntimeException` subclasses only.
- **Never expose internal details in error messages.** "Database error" not "PSQLException: duplicate key".
- **Always log the real error before wrapping:** `log.error("DB error: {}", e.getMessage()); throw new IllegalArgumentException("User-friendly message");`

## 4. Transaction Rules

```java
// WRITE operations — @Transactional (default: read-write)
@Transactional
public MessageResponse sendMessage(UUID channelId, SendMessageRequest req) { ... }

// READ operations — @Transactional(readOnly = true)
@Transactional(readOnly = true)
public List<MessageResponse> getHistory(UUID channelId, Instant before, int limit) { ... }

// Fan-out / Redis operations — NO transaction
// Never wrap Redis calls in a DB transaction
public void fanout(UUID tenantId, UUID channelId, Message msg, UUID senderId, String senderName) { ... }
```

### Critical Rules
- **Never hold a DB transaction during a REST call to another service.** Extract the inter-service call outside the transaction, or use a separate non-transactional method.
- **Never hold a DB transaction during Redis Pub/Sub.** Fan-out must be outside the transaction.
- **Transaction scope should be as small as possible.** Persist to DB → close transaction → then fan-out.

## 5. Concurrency & Race Conditions

### Pattern: Catch-and-Handle for Unique Constraints

```java
// When creating something that might already exist (DM, member, reaction)
try {
    repository.save(entity);
} catch (DataIntegrityViolationException e) {
    // Handle gracefully — either return existing or skip
    log.debug("Already exists, skipping: {}", e.getMessage());
}
```

### Pattern: Atomic Counter Updates

```java
// GOOD — atomic SQL update
@Query("UPDATE messages SET reply_count = reply_count + 1 WHERE id = :id")
void incrementReplyCount(@Param("id") UUID id);

// BAD — read-modify-write (race condition)
Message msg = repo.findById(id);
msg.setReplyCount(msg.getReplyCount() + 1); // RACE: another thread read the same value
repo.save(msg);
```

### Pattern: Idempotent Operations

```java
// DELETE should be idempotent — don't error on "already deleted"
public void deletePin(UUID channelId, UUID messageId) {
    pinnedRepo.deleteByChannelIdAndMessageId(channelId, messageId);
    // Returns 0 rows affected if already deleted — that's fine
}
```

## 6. Input Validation

### Where to Validate

| What | Where | How |
|------|-------|-----|
| Field presence/format | **DTO** | `@NotBlank`, `@NotNull`, `@Size`, `@Email` |
| Business rules | **Service** | `if (!isMember) throw SecurityException` |
| Cross-field validation | **Service** | `if (!hasContent && !hasMedia) throw IllegalArgumentException` |
| Path variable format | **Handler** | UUID auto-parsing (Spring handles bad UUID → 400) |

### Validation Checklist for New Endpoints
- [ ] All required fields have `@NotBlank` / `@NotNull`
- [ ] String fields have `@Size(max = N)` to prevent oversized input
- [ ] UUID fields are validated (Spring auto-parses, throws 400 on invalid)
- [ ] Membership is checked for channel operations
- [ ] Authorization (admin check) for protected operations
- [ ] Resource exists (channel, message) before operating on it
- [ ] Resource belongs to the same tenant (tenant_id check)
- [ ] Resource is not soft-deleted/archived (is_deleted, is_archived check)

## 7. Logging Standards

```java
// Service entry — INFO level for business operations
log.info("Message sent: msgId={} channelId={} senderId={}", msg.getId(), channelId, userId);

// Debug for normal operations
log.debug("Fan-out to server={} for channel={}", serverId, channelId);

// Warn for expected failures (bad input, auth failures)
log.warn("Login failed (wrong password): email={} slug={}", email, slug);

// Error for unexpected failures (DB down, network errors)
log.error("Fan-out failed for msgId={}: {}", msgId, e.getMessage());
```

### Rules
- **MDC is already set** with `tenantId` and `userId` by `JwtAuthFilter`. Don't re-log these.
- **Never log sensitive data:** passwords, JWT tokens, full error stack traces in warn/info.
- **Log at method boundary:** one log per public method call (not inside loops).
- **Include entity IDs in logs** for traceability (msgId, channelId, userId).

## 8. Testing Standards

### Unit Test Rules
- Test every public method in service classes
- Use `@ExtendWith(MockitoExtension.class)` — never load Spring context
- Use `TenantContext.setTenantId()/setUserId()` in `@BeforeEach`, clear in `@AfterEach`
- Test: happy path, validation failures, authorization failures, edge cases
- Name pattern: `methodName_scenario` (e.g., `sendMessage_notMember`)

### Test Case Checklist for New Feature
- [ ] Happy path (all inputs valid, user authorized)
- [ ] Missing required field → 400
- [ ] Resource not found → 400
- [ ] Not a member → 403
- [ ] Not authorized (not admin) → 403
- [ ] Duplicate (unique constraint) → graceful handling
- [ ] Deleted/archived resource → blocked
- [ ] Concurrent operation → no crash
- [ ] Fan-out failure → operation still succeeds

## 9. Anti-Patterns to Avoid

| Anti-Pattern | Why It's Bad | Do This Instead |
|-------------|-------------|-----------------|
| `@Autowired` field injection | Hides dependencies, untestable | `@RequiredArgsConstructor` + `private final` |
| Catching `Exception` broadly | Hides bugs | Catch specific exceptions only |
| Business logic in Handler | Hard to test, violates SRP | Keep handlers thin, logic in services |
| Hardcoded strings for Redis keys | Inconsistent, typo-prone | Use `RedisKeys.*` methods |
| `new RestTemplate()` in service | Untestable, no connection pool | Inject `RestTemplate` bean |
| Returning null from public method | NPE in caller | Return `Optional`, empty list, or throw |
| `System.out.println` | No log level, no MDC | Use `log.info/debug/error` |
| Magic numbers | Unexplained constants | Use named constants: `private static final int MAX_LIMIT = 100;` |
| Mutable shared state | Thread safety issues | Use immutable objects or ThreadLocal |
| String concatenation for SQL | SQL injection | Use `@Query` with `@Param` or JPA method names |

## 10. Performance Rules

- **N+1 Prevention:** Never call the DB in a loop. Batch-fetch first, then iterate.
- **Limit clamping:** Always clamp pagination limit: `Math.max(1, Math.min(limit, 100))`.
- **Eager loading:** Don't eager-load relationships. Use explicit queries.
- **Index awareness:** Every `WHERE` clause column should have an index. Check DATABASE.md.
- **Connection pool:** Don't hold DB connections during I/O waits (REST calls, Redis).
