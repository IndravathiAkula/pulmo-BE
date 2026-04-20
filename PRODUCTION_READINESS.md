# ebookHub — Production Readiness Checklist

A living punch-list of gaps between the current codebase and a production-ready system.
Last audited: **2026-04-17** (full file-by-file review of every module).

Work through items roughly top-to-bottom (P0 → P3). Each item has:

- **Why it matters** — the concrete risk
- **Scope** — files/areas affected
- **Suggested approach** — a starting point, not prescriptive

Mark items `[x]` as you complete them.

---

## Priority Legend

| Tier | Meaning |
|------|---------|
| **P0 — Blocker** | Must fix before accepting real users / money. Security, data-integrity, or revenue risk. |
| **P1 — Critical** | Must fix before public launch. App will break under real load or real adversaries. |
| **P2 — Important** | Should fix within first month of launch. UX / ops pain without it, but not breaking. |
| **P3 — Nice-to-have** | Quality-of-life improvements, DX, long-term maintainability. |

---

## Completed Items

> Items from the original checklist that have been implemented.

- [x] ~~**File Upload Service**~~ — `UploadResource`, `LocalFileStorageService`, `FileServeResource`, `StorageTarget`, `OrphanFileCleanupScheduler` all implemented. Supports cover, preview, book, profile uploads with MIME/size validation and path-traversal defense.
- [x] ~~**Pagination on All List Endpoints**~~ — `PageRequest` + `PagedResponse` DTOs implemented. All 12 list endpoints support `?page=&size=&sort=`. Sort fields are allowlisted per repository.
- [x] ~~**Refresh Token at Rest: Hashed**~~ — `TokenHashUtil.sha256Base64()` used in `SessionService`. Raw refresh tokens are never stored.
- [x] ~~**Checkout Idempotency**~~ — `Idempotency-Key` header supported on both `/payments/checkout` and `/cart/checkout`. Unique constraint on `(user_id, idempotency_key)`.
- [x] ~~**Cart Stale Item Handling**~~ — `CartService.checkout()` now filters out stale items (unpublished, already-owned, author's own) and cleans them up before delegating to `PaymentService`. `addToCart()` is idempotent (returns existing item instead of 409).
- [x] ~~**Orphan File Cleanup**~~ — `OrphanFileCleanupScheduler` runs daily at 03:15, deletes upload files older than 24h not referenced by any DB column.

---

## P0 — Blockers

### [ ] 1. Remove Hardcoded Secrets / Credentials

**Why** — Production-lethal leaks sitting in the repo today:

| Secret | Location | Risk |
|--------|----------|------|
| Admin password `"Admin@12345"` | `AdminSeederService.java:37` — logged in plaintext at line 191 | Anyone reading logs gets admin access |
| Gmail app password `"biny wqcw bvzw qqdv"` | `application-local.properties:34` | SMTP compromise, phishing from your domain |
| DB password `"root"` | `application-local.properties:8` | Direct database compromise |
| Seed secret `"ebookhub-seed-secret-2026"` | `application-local.properties:37` | Attacker can POST `/admin/seed` |
| `privateKey.pem` / `publicKey.pem` | `src/main/resources/` (shipped in JAR) | JWT signing key in build artifact |
| Admin email `taskt600@gmail.com` | `AdminSeederService.java:36`, `EmailService.java:103` | Personal email in prod config |

**Approach**
1. Move ALL secrets to env vars only; remove defaults from code/properties.
2. Generate fresh JWT keypairs per environment; mount private key outside JAR.
3. Rotate every secret listed above — they are already public in git history.
4. Add `.env.example` documenting every required var. Fail startup if critical secrets missing in prod.
5. Never log passwords — use `PasswordGenerator.generate()` for admin and only email it.

---

### [ ] 2. Replace `hbm2ddl=update` with Flyway or Liquibase

**Why** — `quarkus.hibernate-orm.schema-management.strategy=update` silently drifts the prod DB, can't roll back, and already causes constraint-name warnings on every restart. Any schema change in prod is a coin flip.

**Scope** — `application-local.properties`, all `@Entity` classes.

**Approach**
1. Add `quarkus-flyway` dependency.
2. Dump current schema → `V1__baseline.sql`.
3. Switch to `database.generation=validate`.
4. Enable `quarkus.flyway.migrate-at-start=true` for non-prod.

---

### [ ] 3. Implement Real Payment Gateway

**Why** — `PaymentService.checkout()` always sets `status=SUCCESS` without talking to a gateway. Users get books for free.

**Scope** — `PaymentService`, `PaymentResource`, new webhook endpoint.

**Approach**
1. Pick gateway: Razorpay / Stripe / PayU.
2. Two-step: initiate order → FE opens widget → verify server-side with HMAC.
3. Add webhook endpoint as source of truth for payment status.

---

### [ ] 4. Missing Input Validation on Prices and Discounts

**Why** — `CreateBookRequest` and `UpdateBookRequest` have no constraints on `price` or `discount`. Negative prices, discount > price, and extreme values are all accepted. `effectivePrice()` guards against negative results in code but the DB allows invalid states.

**Scope** — `CreateBookRequest.java`, `UpdateBookRequest.java`, `Book.java`.

**Approach**
1. Add `@DecimalMin("0.00")` and `@DecimalMax("99999.99")` to price fields in DTOs.
2. Add custom validation: `discount <= price`.
3. Add DB constraints: `CHECK (price >= 0)`, `CHECK (discount >= 0 AND discount <= price)`.

---

### [ ] 5. Missing @Size / Length Constraints on All DTOs

**Why** — Most DTO string fields lack `@Size` constraints. An attacker can send megabyte-long strings for `firstName`, `lastName`, `description`, `deviceFingerprint`, `userAgent`, etc. causing DB column overflow, log explosion, and memory exhaustion.

**Scope** — Every DTO in `auth/dto/`, `catalog/dto/`, `commerce/dto/`, `user/dto/`, `admin/dto/`.

**Approach** — Add `@Size(max=N)` to every user-provided string field:

| Field type | Max length |
|-----------|-----------|
| Names (first, last) | 100 |
| Email | 255 |
| Title | 255 |
| Description, bio | 5000 |
| Phone | 20 |
| Keywords | 500 |
| Device fingerprint | 256 |
| Rejection reason | 1000 |
| URL fields | 2048 |

---

### [ ] 6. Duplicate Payments Without Idempotency Key

**Why** — `Idempotency-Key` header is optional. If omitted, two concurrent checkout requests create two separate SUCCESS payments for the same books. `NULL != NULL` in SQL, so the unique constraint `(user_id, idempotency_key)` does not prevent duplicates when key is null.

**Scope** — `PaymentService.checkout()`, `CheckoutRequest`, `PaymentResource`, `CartResource`.

**Approach**
1. Make `Idempotency-Key` mandatory on checkout endpoints.
2. Or auto-generate a key server-side from `hash(userId + sorted bookIds)` as a fallback.
3. Or add a secondary dedup: unique constraint on `(user_id, created_at::date, book_ids_hash)`.

---

### [ ] 7. Book Price Can Change Mid-Checkout

**Why** — Books are loaded once in `PaymentService.checkout()`. If an admin changes `Book.price` between load and payment creation, `PaymentItem` records use stale prices while `PaymentHistory.amount` uses the in-memory total. Ledger mismatch.

**Scope** — `PaymentService.checkout()`.

**Approach**
1. Use `SELECT ... FOR UPDATE` (pessimistic lock) on Book rows during checkout.
2. Or snapshot prices at fetch time and use only the snapshot for computation.
3. Add a reconciliation check: `assert sum(items.effectivePrice) == payment.amount` before commit.

---

### [ ] 8. `getProfile()` Crashes for Users Without Profile Row

**Why** — `UserProfileService.getProfile()` throws `ResourceNotFoundException("Profile not found")` if no `t_user_profiles` row exists. Seeded admin users, users created before profile logic was added, or users whose profile was somehow deleted will crash the dashboard.

**Scope** — `UserProfileService.getProfile()`, `AdminSeederService`.

**Approach**
1. Auto-create a blank profile in `getProfile()` if none exists (lazy initialization).
2. Or ensure every user-creation path (register, admin seed, admin create author) always creates a profile.

---

## P1 — Critical Before Public Launch

### [ ] 9. Extend Rate Limiting Beyond `/auth/*`

**Why** — `RateLimitFilter` only covers auth endpoints. `POST /payments/checkout`, `POST /books`, `POST /uploads` are unthrottled.

**Scope** — `RateLimitFilter.java`.

**Approach**
1. Per-user limits: checkout 10/min, book create 30/hour, uploads 20/hour.
2. Per-IP on public GETs: `/books*` 120/min.
3. Replace in-memory Caffeine with Redis for horizontal scaling.
4. Fix TOCTOU race condition in `RateLimitBucket.incrementAndCheck()` — use `compareAndSet()`.

---

### [ ] 10. Rate Limiter: X-Forwarded-For Spoofing

**Why** — `RateLimitFilter` and all `extractClientIp()` methods trust `X-Forwarded-For` without validation. Attacker can rotate spoofed IPs to bypass rate limits entirely.

**Scope** — `RateLimitFilter.java:138`, `AuthResource.java`, `CartResource.java`, `PaymentResource.java`, `AdminResource.java`.

**Approach**
1. Validate IP format (IPv4/IPv6 regex).
2. Only trust `X-Forwarded-For` behind a known reverse proxy.
3. Truncate to max 45 chars. Fallback to direct connection IP from `HttpServerRequest`.

---

### [ ] 11. Email Service Swallows Exceptions

**Why** — `EmailService.sendHtml()` catches all exceptions and only logs. If SMTP is down, user registration succeeds but verification email never arrives. User is permanently stuck in unverified state.

**Scope** — `EmailService.java:137-145`.

**Approach**
1. Transactional outbox: write email to `t_email_outbox` in same TX; scheduler retries with exponential backoff.
2. Or at minimum, propagate the exception on critical paths (verification, password reset) so the caller can fail gracefully.
3. Consider SES / SendGrid for prod (Gmail daily cap = 500).

---

### [ ] 12. Session Revocation Not Checked on Token Lookup

**Why** — `SessionService.findValidSessionByToken()` checks `expiresAt` but does NOT check `session.isRevoked()`. A revoked session can still be used for refresh. The `revoked` field is set during logout and rotation but never validated during lookup.

**Scope** — `SessionService.java:58-65`.

**Approach**
```java
if (session.isRevoked() || session.getExpiresAt().isBefore(Instant.now())) {
    return Optional.empty();
}
```

---

### [ ] 13. Harden `/admin/seed` or Remove It

**Why** — `@PermitAll` + shared-secret header. If secret is leaked, anyone can seed prod DB.

**Scope** — `AdminResource.seed()`.

**Approach**
1. Remove endpoint entirely in prod; run seeding as a CLI job.
2. If kept: require ADMIN JWT plus secret, rate-limit to 1/day, alert on every invocation.

---

### [ ] 14. CORS from Environment — Not Hardcoded

**Why** — `application-local.properties` hardcodes `localhost:3000,localhost:5173`. First prod deploy ships with localhost CORS.

**Scope** — `application-local.properties`.

**Approach** — `quarkus.http.cors.origins=${CORS_ORIGINS:http://localhost:3000}`, per-profile properties.

---

### [ ] 15. Health Checks for External Dependencies

**Why** — `/q/health` returns UP even if DB pool is exhausted or SMTP is unreachable.

**Approach** — `DatabaseHealthCheck` (SELECT 1), `SmtpHealthCheck` (TCP probe), `StorageHealthCheck`.

---

### [ ] 16. Book Status State Machine Not Enforced

**Why** — No validation prevents invalid state transitions. APPROVED → PENDING cycles are allowed. DELETED books have no guard preventing future operations. An author can revert admin approval by submitting an update that triggers status reset.

**Scope** — `BookService.java` (approve/reject/update/delete methods).

**Approach**
1. Create an explicit state machine: `PENDING → APPROVED | REJECTED`, `REJECTED → PENDING` (resubmit), `APPROVED → DELETED`, etc.
2. Validate transition in service layer before applying.
3. Prevent any mutation on `DELETED` books.

---

### [ ] 17. N+1 Query: `resolveAuthorName()` in Book Listings

**Why** — `BookService.toResponse()` calls `userProfileRepository.findByUserId()` for every book. A page of 20 books = 20 extra queries. Same pattern in `PaymentService.toResponse()` and `toPurchasedBookResponse()`.

**Scope** — `BookService.java:564-587`, `PaymentService.java:259-286,288-319`.

**Approach**
1. Batch-load all author profiles in a single `WHERE user_id IN (...)` query before mapping.
2. Or add a `JOIN FETCH` to `UserProfile` in the book query.
3. Or cache author name resolution (profiles rarely change).

---

### [ ] 18. N+1 Query: Payment Items in Payment History

**Why** — `PaymentService.getUserPayments()` loops through each payment and calls `paymentItemRepository.findByPaymentId()` individually. 100 payments = 101 queries.

**Scope** — `PaymentService.java:198-204`.

**Approach** — Batch-load all items for all payment IDs in a single query, then group by payment ID in memory.

---

### [ ] 19. Author Email Leaked in Cart Response

**Why** — `CartService.toCartItemResponse()` sets `authorName = book.getAuthor().getEmail()` instead of the author's display name. Every user viewing their cart sees the author's email address.

**Scope** — `CartService.java:196`.

**Approach** — Use the same `resolveAuthorName()` pattern from `PaymentService` (first + last name, fallback to email).

---

### [ ] 20. Observability — Correlation IDs, Metrics, Structured Logs

**Why** — No way to trace a single request across logs. No SLO dashboards. Prod debugging = guessing.

**Approach**
1. Correlation ID filter — read `X-Request-ID` or generate UUID, put in MDC.
2. Structured logging — JSON output in prod.
3. Metrics — `quarkus-micrometer-registry-prometheus`, business metrics.
4. Redact JWT/password/tokens from request logs.

---

### [ ] 21. Expired Token / Session Cleanup Jobs

**Why** — `t_password_reset_tokens`, `t_email_verification_tokens`, revoked/expired `t_sessions` accumulate forever. Table bloat, stale data.

**Scope** — New `@Scheduled` beans.

**Approach** — Daily sweeper: delete expired tokens, revoked sessions older than N days.

---

### [ ] 22. Logout IDOR — Missing Session Ownership Check

**Why** — `AuthService.logout()` accepts a `userId` and `refreshToken` but doesn't verify `session.getUser().getId().equals(userId)`. If an attacker has a refresh token, they can log out any user.

**Scope** — `AuthService.java:199-208`.

**Approach** — Add ownership check before revoking.

---

## P2 — Important

### [ ] 23. Search + Filtering on Public Book Catalog

**Why** — No way for a user to search for books. Frontend must fetch all and filter client-side.

**Scope** — `GET /books`, `BookRepository`, `BookService`.

**Approach** — `?q=<search>&categoryId=&minPrice=&maxPrice=`. Phase 1: `ILIKE`. Phase 2: Postgres full-text search.

---

### [ ] 24. Test Suite (unit + integration)

**Why** — `src/test/java` is empty. Every release is a coin flip.

**Approach** — `@QuarkusTest` + Testcontainers. Cover: register → verify → login, book create → approve, checkout.

---

### [ ] 25. Zero-Amount Payment Guard

**Why** — If all books in a checkout have `effectivePrice = 0` (discount equals price), a payment with `amount=0` and `status=SUCCESS` is created. Pollutes ledger and grants free books.

**Scope** — `PaymentService.checkout()`.

**Approach** — Either block zero-amount payments, or create a separate "free claim" flow.

---

### [ ] 26. Hibernate Constraint Warnings on Startup

**Why** — Every restart logs ~15 "constraint does not exist, skipping" warnings because several entities use `@Column(unique = true)` which generates unpredictable constraint names. Noisy logs obscure real issues.

**Scope** — `Category`, `ConfigParam`, `Role`, `EmailVerificationToken`, `PasswordResetToken`, `UserProfile`, `User` entities.

**Approach** — Replace all `@Column(unique = true)` with `@Table(uniqueConstraints = @UniqueConstraint(name = "uk_...", columnNames = {...}))`.

---

### [ ] 27. Password Complexity Enforcement

**Why** — Only `@Size(min=8)` on password fields. An 8-char all-lowercase password is accepted. Weak passwords are easy to crack.

**Scope** — `RegisterRequest`, `ResetPasswordRequest`, `ChangePasswordRequest`.

**Approach** — Custom `@PasswordComplexity` validator: >= 1 uppercase, 1 lowercase, 1 digit, 1 special char. Or minimum 12 chars.

---

### [ ] 28. CSRF Protection

**Why** — No CSRF tokens or SameSite cookie settings configured. State-changing POST/PUT/DELETE endpoints vulnerable to cross-site request forgery.

**Scope** — Global filter or Quarkus CSRF extension.

**Approach** — Set `SameSite=Strict` on cookies. Validate `Origin`/`Referer` headers on state-changing requests.

---

### [ ] 29. Refresh Token in HTTP-Only Cookie

**Why** — Refresh tokens returned in JSON body. Frontend likely stores in `localStorage` which is XSS-vulnerable. Access tokens are short-lived (acceptable), but refresh tokens are long-lived (30 days).

**Scope** — `AuthResource.login()`, `AuthResource.refresh()`.

**Approach** — Return refresh token as `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/ebook/auth`.

---

### [ ] 30. OpenAPI / Swagger Annotations

**Why** — `smallrye-openapi` is present but resources have no `@Operation` / `@Schema`. Auto-generated spec is vague.

**Approach** — Annotate endpoints and DTOs. Generate TypeScript types with `openapi-typescript`.

---

### [ ] 31. Input Sanitization / XSS on Rich-Text Fields

**Why** — `book.description`, `userProfile.description` are `TEXT` with no sanitization. Stored XSS possible if rendered as HTML.

**Approach** — Document contract: descriptions are plain text. Escape on render. Add length caps (5000 chars).

---

### [ ] 32. Soft-Delete Consistency

**Why** — `BookStatus=DELETED` and `User.status=DISABLED` exist but individual queries may forget the filter.

**Approach** — Audit every `find*` query; ensure deleted/disabled entities are filtered.

---

### [ ] 33. Argon2 Password Hashing Parameters

**Why** — Current: `time=2, memory=64MB, parallelism=1`. OWASP 2024+ recommends `time=4-8, parallelism=2-8`. Current params may be too weak for 2026 hardware.

**Scope** — `PasswordService.java:13,17`.

**Approach** — Make configurable via `ConfigService`. Increase to `time=4, memory=64MB, parallelism=4`.

---

### [ ] 34. Page Number Upper Bound

**Why** — `PageRequest` caps page size at 100 but has no limit on page number. `?page=999999999` causes `OFFSET 99999999900` which hammers the database.

**Scope** — `PageRequest.java:48`.

**Approach** — Add `if (page > 10000) throw ValidationException`.

---

### [ ] 35. Audit Log for All Admin/Business Actions

**Why** — Audit events exist for auth flows but not for cart operations, category changes, or profile updates. Disputes are unresolvable without complete audit trail.

**Scope** — `CartService`, `CategoryService`, `UserProfileService`.

**Approach** — Standardize: every mutating action writes an `AuditEvent` row.

---

## P3 — Nice-to-Have

### [ ] 36. CI/CD Pipeline

**Why** — No `.github/workflows`. Humans pushing to prod by hand.

**Approach** — GitHub Actions: on PR → test, on main → build + deploy to staging.

---

### [ ] 37. Containerization — Dockerfile + docker-compose

**Why** — Local onboarding requires manual Java + Postgres + seeding setup.

**Approach** — `docker-compose.yml` with postgres, minio, mailhog for dev.

---

### [ ] 38. Email Templates via Qute

**Why** — Email HTML is concatenated in Java strings. Changes require code deploys.

**Approach** — Move to `src/main/resources/templates/email/*.qute.html`.

---

### [ ] 39. Book Reviews / Ratings

**Why** — No reviews/ratings surface. Discovery and SEO both suffer.

---

### [ ] 40. Refund Flow

**Why** — `PaymentStatus.REFUNDED` exists in enum but no refund logic. If a payment is manually set to REFUNDED, `UserBook` access is NOT revoked.

**Approach** — Implement refund endpoint that revokes `UserBook` rows and creates a refund payment record.

---

### [ ] 41. GDPR / Data Export + Delete

**Why** — EU users require `GET /user/me/export` and `DELETE /user/me`.

---

### [ ] 42. ConfigService Cache Expiry

**Why** — Config params are cached at startup and only refreshed via `refreshCache()`. DB-direct config changes are invisible until restart.

**Approach** — Add TTL-based cache expiry (e.g., 5 minutes) or a scheduled refresh.

---

### [ ] 43. Idempotency Key TTL

**Why** — Idempotency keys are stored forever. Reusing a key 6 months later still deduplicates (confusing).

**Approach** — Exclude rows older than 24-48h from idempotency lookup.

---

### [ ] 44. Localization / i18n

**Why** — Every error message is English. Multi-region launch needs `Accept-Language` handling.

---

### [ ] 45. Background Job Queue

**Why** — Email retry, audit archival, thumbnail generation all need a real queue. `@Scheduled` doesn't scale horizontally.

**Approach** — Postgres-backed queue, graduate to Redis/Kafka when needed.

---

## Bug Log

> Known bugs discovered during the 2026-04-17 audit.

| # | Severity | Bug | Location | Status |
|---|----------|-----|----------|--------|
| B1 | P0 | `findValidSessionByToken()` doesn't check `revoked` flag — revoked sessions can still refresh | `SessionService.java:58-65` | Open — see item #12 |
| B2 | P1 | `findAdminUser()` returns `null` when admin doesn't exist; callers don't null-check → NPE on book approval | `BookService.java:401-405` | Open |
| B3 | P1 | `CartService.toCartItemResponse()` exposes author's email as `authorName` | `CartService.java:196` | Open — see item #19 |
| B4 | P1 | `getProfile()` throws 404 for users without profile row (e.g., seeded admin) instead of auto-creating | `UserProfileService.java:47-48` | Open — see item #8 |
| B5 | P2 | Hibernate logs ~15 "constraint does not exist" warnings on every fresh startup due to `@Column(unique=true)` | All entities with column-level unique | Open — see item #26 |
| B6 | P2 | `RejectBookRequest` not annotated with `@Valid` in `AdminResource.rejectBook()` — validation not enforced | `AdminResource.java:196` | Open |
| B7 | P2 | Category slug generation collision: "Café" and "Caf" both become "caf" | `CategoryService.java:178-184` | Open |
| B8 | P3 | `PaymentItem.discount` column is nullable but accessed without null guard in `toResponse()` | `PaymentService.java:267` | Open |

---

## Risk Register

> Architectural and operational risks that don't have a specific fix but need awareness.

| # | Risk | Impact | Mitigation |
|---|------|--------|------------|
| R1 | **Single-node rate limiter** — in-memory state is lost on restart and not shared across pods | Rate limit bypass during restarts or horizontal scaling | Migrate to Redis (documented in `RateLimitFilter`) |
| R2 | **Race condition: author update + admin approval** — author submitting update while admin approves can overwrite approval status | Approval workflow corruption | Add `@Version` optimistic lock check on status field |
| R3 | **File storage on local disk** — `public/uploads/` is ephemeral; `./gradlew clean` or container restart loses files | All uploaded files lost | Migrate to S3/MinIO for prod; local is dev-only |
| R4 | **Gmail SMTP cap** — Gmail allows 500 emails/day. At scale, verification and reset emails will silently fail | Users unable to verify or reset passwords | Migrate to SES/SendGrid/Mailgun |
| R5 | **No audit on cart operations** — add/remove/clear cart has `LOG.infof` but no `auditService.logEvent()` | Cannot reconstruct user cart history for disputes | Add audit events to cart operations |
| R6 | **Partial fulfillment on concurrent grant** — if admin grants book access mid-checkout, `UserBook` insert is silently skipped but payment still shows SUCCESS | Payment amount doesn't match granted books | Log and alert on skipped grants |

---

## How to Use This List

1. **Don't multitask P0s** — one at a time, merge, deploy, move on.
2. When you start an item, open a branch named `readiness/<number>-<slug>`.
3. When you finish, update this file in the same PR (check the box, add a "resolved" note).
4. Re-prioritize weekly — something in P2 may become P0 after first real user feedback.

**Quick sanity check before ship-to-prod:** items 1–8 (P0) and 9–22 (P1) are all green.
