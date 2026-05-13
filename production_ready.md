# Production Readiness Review — ebookHub Backend

> **Scope:** Quarkus 3.26.1 + Gradle + PostgreSQL + Hibernate ORM Panache + SmallRye JWT
> **Base package:** `com.ebook`
> **Date of review:** 2026-04-22
> **Reviewer:** Senior Java + Quarkus + DevOps architect

---

## 1. Executive Summary

### Overall Readiness Score: **58 / 100**

The codebase is architecturally coherent, uses appropriate building blocks (Argon2id, SmallRye JWT, Panache, Quarkus Mailer, idempotent checkout), and shows evidence of iterative hardening (refresh-token reuse detection, price snapshots, ledger reconciliation, rate-limit fallback, exception mapping). However, several **P0 blockers** prevent it from being deployed to a real user base today — principally a hardcoded seeder admin password exposed via an externally callable endpoint, permissive CORS, untrusted `X-Forwarded-For`, a non-sharable in-memory rate limiter, a JWT signing key assumed to live on the filesystem, blocking SMTP on the request thread, and schema-management `update` on a prod database.

### Top Key Risks (P0)

| # | Risk | Where | Impact |
|---|------|-------|--------|
| 1 | Hardcoded admin credentials `taskt600@gmail.com / Admin@12345` + seed secret default in source | `AdminSeederService`, `application-local.properties` | Full admin takeover of any deployment that forgets to rotate |
| 2 | `quarkus.http.cors.origins=*` with credentialed auth headers | `application-local.properties:31` | Any website can call the API with a pasted JWT |
| 3 | `security.trust-forwarded-for` defaults to `true` | `AuthResource`, `RateLimitFilter`, `AdminResource` | IP spoofing defeats rate limiting, pollutes audit logs, forges password-reset origins |
| 4 | `quarkus.hibernate-orm.schema-management.strategy=update` | `application-local.properties:20` | Schema drift, data loss risk, no migration history |
| 5 | In-memory `ConcurrentHashMap` rate limiter | `RateLimitFilter` | Breaks as soon as a second instance runs; per-pod buckets = N× real quota |
| 6 | JWT private/public key paths hardcoded to `privateKey.pem` / `publicKey.pem` | `application-local.properties:24-25` | Key rotation impossible; fails in immutable containers without externalization |
| 7 | SMTP send is synchronous on the request worker thread, and a failing send is logged then swallowed | `EmailService.sendHtml` | Latency spikes, lost verification / reset mails with no retry |
| 8 | No database migration tool (Flyway/Liquibase) | Nowhere | Production schema evolution is unsafe |

### Top 5 Improvements (do these first)

1. **Replace `schema-management=update` with Flyway** and ship a baseline migration before any production release.
2. **Eject seeder out of HTTP.** Move it to a startup `@Observes StartupEvent` gated by `quarkus.profile=dev` **or** a one-shot Gradle task; drop `DEFAULT_ADMIN_PASSWORD` entirely (generate + log once or require `ADMIN_BOOTSTRAP_PASSWORD` env var).
3. **Lock down CORS** to an explicit origin list + disallow `*` when credentials are sent; move `security.trust-forwarded-for` default to `false` and only flip it on behind a known reverse proxy.
4. **Externalize the rate limiter** to Redis (`quarkus-redis-client`) or cache (`quarkus-cache`); keep the in-memory one only for local dev.
5. **Send email asynchronously** (via `ReactiveMailer` or a dedicated `ManagedExecutor`), add retries, and surface failures as metrics instead of swallowing them.

---

## 2. Architecture Overview

```
                       ┌───────────────────────────────┐
                       │  JAX-RS Resources             │
                       │  /auth, /books, /cart,        │
                       │  /payments, /admin, /uploads, │
                       │  /files, /health, /config     │
                       └──────────────┬────────────────┘
                                      │   (JsonWebToken, @RolesAllowed)
                       ┌──────────────▼────────────────┐
                       │  Services (@ApplicationScoped) │
                       │  AuthService, JwtService,      │
                       │  SessionService, PaymentSvc,   │
                       │  CartSvc, BookSvc, AdminSvc    │
                       └──────────────┬────────────────┘
                                      │   (@Transactional at service layer)
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
┌───────▼────────┐       ┌────────────▼────────────┐     ┌──────────▼─────────┐
│ Panache Repos  │       │  Filters / Providers     │     │  Scheduler         │
│ (hibernate)    │       │  RateLimitFilter,        │     │  TokenCleanup 1h   │
│ postgres       │       │  GlobalExceptionHandler  │     │                    │
└────────────────┘       └──────────────────────────┘     └────────────────────┘
        │
┌───────▼────────┐       ┌──────────────────────────┐     ┌────────────────────┐
│ PostgreSQL     │       │  Local-disk FileStorage  │     │  Gmail SMTP        │
│ (single DB)    │       │  /tmp/uploads            │     │  app-password      │
└────────────────┘       └──────────────────────────┘     └────────────────────┘
```

**Module map (by package):**

- `auth` — registration, login, refresh-token rotation + reuse detection, email verification, password reset, change-password, sessions, devices, roles, audit log.
- `user` — `User`, `UserProfile`, `UserBook` (entitlements), `RecentBook`, `BookAccessLog`.
- `catalog` — `Book` with approval workflow (PENDING → APPROVED/REJECTED/DELETED), `BookApprovalLog`, `Category`.
- `commerce` — `CartItem`, `PaymentHistory`, `PaymentTransaction` (line items), mock checkout with `Idempotency-Key` replay.
- `admin` — author CRUD for admins, book approval, seeder (`/admin/seed` gated by `X-Seed-Secret`).
- `common` — `BaseEntity` (UUID + timestamps), `ApiResponse`/`ErrorResponse`, rate-limit filter, global exception mapper, Quarkus Scheduler cleanup, file storage (local / pluggable), config param runtime overrides, email, utilities.

**Layering is correct** — resources stay thin, `@Transactional` lives on services, repositories are Panache. DI is constructor-based (good). Entities inherit UUID + createdAt/updatedAt from `BaseEntity`. `BaseEntity.equals()` is implemented correctly (id-based, null-safe). Jackson `@JsonIgnore` is applied to `User.passwordHash` and `Book.fileKey` (good).

---

## 3. File-by-File Analysis

> Where a section lists "See class-level notes" it means the issue repeats across many similar files (e.g. every resource duplicating `extractUserId()` / `extractClientIp()`); that issue is flagged once and carried forward.

### 3.1 Build & Configuration

#### `build.gradle`

- **Purpose:** Gradle build file for the Quarkus 3.26 platform, declares runtime and test deps.
- **Issues:**
  1. Uses `mavenLocal()` alongside `mavenCentral()` — fine for dev, **risky for CI** (a developer's local mutation can leak into a build). Prefer a controlled internal repo.
  2. No `quarkus-container-image-docker` / `quarkus-container-image-jib` plugin. The current Dockerfile uses `./gradlew build` inside the image, which is slow and ships JDK + Gradle cache in the final layer (see `Dockerfile` findings).
  3. No `quarkus-micrometer` / `quarkus-opentelemetry` — there are **zero runtime metrics or traces**. `smallrye-health` is present but not split into liveness vs readiness groups.
  4. No `quarkus-flyway` or `quarkus-liquibase` — pair this with the `schema-management=update` finding below.
  5. `argon2-jvm` version is pinned (2.11) but ties the project to JNA-based native lib. Ensure the Docker base image has `glibc` (fine for `eclipse-temurin:17-jdk`, not for Alpine).
  6. `mapstruct` is on the classpath but **no mapper is used** in the codebase — all DTO mapping is hand-written. Either use MapStruct or drop the dependency.
  7. No test coverage plugin (`jacoco`) configured. No code-style plugin (`checkstyle`/`spotless`).
  8. `options.compilerArgs << '-parameters'` is good (needed for bean validation path messages and for Panache parameter binding).
  9. No `test` task configuration that sets `environment`/profile — tests will inherit whatever `quarkus.profile=local` implies from `application.properties`, including **the real SMTP creds** if env vars leak.
- **Improvements:**
  - Add Flyway, Micrometer Prometheus registry, OpenTelemetry, Container Image Jib, and separate test/native source sets.
  ```groovy
  implementation 'io.quarkus:quarkus-flyway'
  implementation 'io.quarkus:quarkus-micrometer-registry-prometheus'
  implementation 'io.quarkus:quarkus-opentelemetry'
  implementation 'io.quarkus:quarkus-container-image-jib'
  implementation 'io.quarkus:quarkus-cache'        // for rate-limit fallback
  implementation 'io.quarkus:quarkus-redis-client' // for distributed rate limit
  ```

#### `gradle.properties`

- **Purpose:** Pins the Quarkus platform version to 3.26.1.
- **Issues:**
  1. Commented-out 3.32.4 at the top suggests a failed upgrade — leaving dead config invites accidental reverts.
  2. No `org.gradle.jvmargs` / `org.gradle.parallel` / `org.gradle.caching` — CI builds are slower than they need to be.
- **Improvements:** Remove the dead block; add `org.gradle.jvmargs=-Xmx2g`, `org.gradle.parallel=true`, `org.gradle.caching=true`.

#### `settings.gradle`

- **Purpose:** Plugin management and project name.
- **Issues:** Project name is `ebook` but the directory is `ebook-be`. Not load-bearing, just confusing.
- **Improvements:** `rootProject.name='ebook-be'`.

#### `src/main/resources/application.properties`

- **Purpose:** Default Quarkus profile pointer.
- **Issues:**
  1. Sets `quarkus.profile=local` **as a default in `application.properties`**, which means every non-overridden environment (prod, test, staging) silently runs as `local`. This is almost certainly the reason production relies on `application-local.properties` — which is the real config file.
  2. No `%prod.*` or `%dev.*` overlays at all.
- **Improvements:**
  - Delete the `quarkus.profile=local` line; rely on `QUARKUS_PROFILE=prod` env var in deployments.
  - Rename `application-local.properties` → `application.properties`, then peel environment-specific values into `%prod.*` / `%dev.*` overlays.

#### `src/main/resources/application-local.properties`

- **Purpose:** The *actual* runtime config — HTTP, DB, CORS, JWT, SMTP, storage, body limits.
- **Issues:**
  1. **`quarkus.http.cors.origins=*`** (line 31) — wide-open CORS with `Authorization` as an allowed header. With JWT in `Authorization`, this effectively invites token misuse from any origin. Spec requires an explicit origin list when `Access-Control-Allow-Credentials` is in play.
  2. **DB creds required env vars (`${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`) have no defaults** — which is actually correct for security. Good.
  3. `quarkus.datasource.jdbc.max-size=10`, `min-size=2`, `acquisition-timeout=30` — **`acquisition-timeout=30` is 30 **seconds** if interpreted as seconds, or 30 ms if Quarkus reads it as `Duration`.** The Quarkus property is a `Duration` and `30` is parsed as 30 seconds; fine, but should be written `30S` for clarity. Pool size of 10 is small for a production workload — benchmark before ship.
  4. `quarkus.datasource.jdbc.max-lifetime=300` → 5 minutes is fine; `idle-removal-interval=60` → 1 minute. Acceptable.
  5. **`schema-management.strategy=update`** (line 20) — unsafe in prod. Use `none` + Flyway.
  6. `smallrye.jwt.sign.key.location=privateKey.pem` / `mp.jwt.verify.publickey.location=publicKey.pem` — the PEMs sit on the filesystem with no mention of rotation, no JWKS indirection (aside from `InternalSsoResource` which reads `publicKey.json` from the classpath). The private key on the filesystem is a **secrets-handling red flag**: containers bake it in or mount it; rotation means a redeploy. Use `smallrye.jwt.sign.key.location=/run/secrets/jwt-priv.pem` (Docker/K8s secret) and load via a volume.
  7. **SMTP password committed as a default** (line 45: `${MAIL_PASSWORD:biny wqcw bvzw qqdv}`). This is a Gmail app-password. Anyone cloning the repo gets send-as access. Revoke immediately.
  8. **Seed secret default** (`${SEED_SECRET:ebookhub-seed-secret-2026}`) — predictable, in source; combined with the seeder endpoint being `@PermitAll`, a fresh deployment is admin-takeover material.
  9. `quarkus.mailer.mock=false` in the local file — means local development will actually try to send email unless env override is provided.
  10. `quarkus.mailer.auth-methods=LOGIN` forces LOGIN only; Gmail also accepts PLAIN over TLS — not a real problem.
  11. `storage.local.base-dir=/tmp/uploads` — **on container restart, /tmp is wiped**. Uploads will disappear. Use a persistent volume or S3 from day one.
  12. `storage.local.public-base-url=/ebook/files` — fine.
  13. `quarkus.http.limits.max-body-size=150M` — set to cover the BOOK 100 MB ceiling; correct, but consider denial-of-service implications (one upload eats 100 MB of heap if Quarkus buffers in memory — `handle-file-uploads=true` streams to `uploads-directory`, so OK).
  14. `${QUARKUS_UPLOADS_DIR:target/uploads-tmp}` is a dev-only path; in prod ensure this is also a real volume.
  15. **No `%prod.*` overlay** exists anywhere, so a prod deploy silently uses these local-centric values.
  16. `quarkus.http.cors.access-control-max-age=24H` — allowed values are `Duration`; `24H` → 24 hours preflight cache. Acceptable, but when origins are opened up selectively, consider lowering during rollout.
  17. No `quarkus.http.cors.access-control-allow-credentials` set. CORS with `origins=*` plus credentials would be rejected by browsers, but a production fix must include `allow-credentials=true` with an explicit origin list.
  18. No `quarkus.http.access-log.enabled=true` / no structured log config. No JSON access logs.
  19. No readiness vs liveness split.
- **Improvements:**
  ```properties
  # application.properties (default/shared)
  quarkus.http.root-path=/ebook
  quarkus.hibernate-orm.database.generation=none
  quarkus.flyway.migrate-at-start=true
  quarkus.log.console.json=true
  quarkus.http.cors=true
  quarkus.http.cors.access-control-allow-credentials=true

  # %prod overlay
  %prod.quarkus.http.cors.origins=${APP_FRONTEND_ORIGIN}
  %prod.security.trust-forwarded-for=${TRUST_FORWARDED_FOR:false}
  %prod.quarkus.hibernate-orm.schema-management.strategy=none
  %prod.storage.provider=s3
  %prod.quarkus.mailer.mock=false
  ```

#### `Dockerfile`

- **Purpose:** Builds and runs the Quarkus fast-jar.
- **Issues:**
  1. **Single-stage build** — ships JDK + Gradle wrapper + source + `.gradle/` cache into the final image. Image is many hundreds of MB larger than necessary.
  2. `COPY . .` copies `.git`, `build/`, IDE dirs, `.env*`, `target/`, logs — everything in `.gitignore` is still on the build context. `.dockerignore` only contains 46 bytes; verify it lists all of these.
  3. `CMD ["java","-jar", …]` with no JVM flags — no `-XX:MaxRAMPercentage=75.0`, no `-Dquarkus.http.host=0.0.0.0`. Container memory-awareness depends on JDK 17 defaults (OK), but explicit flags are safer.
  4. Builds from `eclipse-temurin:17-jdk` instead of a runtime-only `17-jre` / `jlink` image.
  5. No `USER nonroot` — container runs as root.
  6. No healthcheck.
  7. A developer-shell comment `🔥 ADD THIS LINE (fix permission)` is baked into the prod image file; trivial but reveals the build was fire-fought.
- **Improvements:**
  ```dockerfile
  # ---- build stage ----
  FROM eclipse-temurin:17-jdk AS build
  WORKDIR /src
  COPY gradle gradle
  COPY gradlew settings.gradle build.gradle gradle.properties ./
  RUN ./gradlew --no-daemon help   # warms wrapper
  COPY src src
  RUN ./gradlew --no-daemon build -x test

  # ---- runtime stage ----
  FROM eclipse-temurin:17-jre AS runtime
  WORKDIR /app
  COPY --from=build /src/build/quarkus-app /app
  RUN addgroup --system app && adduser --system --ingroup app app
  USER app
  EXPOSE 8080
  HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://localhost:8080/ebook/q/health/ready || exit 1
  ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/quarkus-run.jar"]
  ```

#### `.gitignore`

- Correctly ignores `*.pem`, `*.key`, `.env`, `.env.*`, `build/`, `bin/`, `target/`. Good.
- **Watch item:** `bin/` is still checked in under `bin/main/...` in the status snapshot — Git tracked it before the ignore was added. Run `git rm -r --cached bin` before the next commit.

---

### 3.2 Auth Module

#### `auth/resource/AuthResource.java`

- **Purpose:** Registration, login, token refresh, logout, forgot/reset password, email verification, change-password, `/me`.
- **Issues:**
  1. **`security.trust-forwarded-for` defaults to `true`** (line 29). When the app is exposed without a reverse proxy this lets any client forge their source IP in audit logs, password-reset events, and rate-limit buckets.
  2. **No CSRF protection**. JWT in `Authorization` defeats CSRF *if* clients never store it in a cookie, but this isn't enforced anywhere; if a frontend ever stores JWT in cookies, forgot/change-password become CSRF-able.
  3. **Enumeration side-channel:** `/register` (line 38) returns `ConflictException("Email is already registered")`. Contrast with `/forgot-password` (line 88) which intentionally does not disclose email existence. Register should not either — return `201 Created` with a generic response and send "account exists — try logging in" email if desired.
  4. `/refresh` has `@POST` with no role annotation — correct, because the refresh token *is* the credential. Watch: if a future change adds `@RolesAllowed` here it would break unauthenticated refresh flow.
  5. `/change-password` correctly revokes all sessions in the service (AuthService:371) — but there is no subsequent issuance of new tokens; clients must re-login. That's intentional per the service docstring; make sure the frontend handles it.
  6. `extractUserId()` (line 141) calls `UUID.fromString(subject)` — malformed `sub` → `IllegalArgumentException`, which is now mapped to 400 by `GlobalExceptionHandler`. OK.
  7. **Duplicate boilerplate:** every resource (Auth, Admin, Payment, etc.) has its own copy of `extractUserId()` + `extractClientIp()`. Six copies total.
  8. No `@Valid` on `RefreshRequest` for `/logout` — technically valid, but inconsistent. `/logout` accepts `@Valid RefreshRequest` (line 67), OK.
  9. The method signature for `/logout` requires a refresh token in the body — correct (revokes that specific session), but if the client has lost the refresh token (e.g. stolen) they can still `/logout-all`. Good.
- **Improvements:**
  - Extract a `@RequestScoped` `CallerContext` bean that exposes `userId()` and `clientIp()` and inject it everywhere. Removes all six copies.
  - Default `security.trust-forwarded-for=false` and use `quarkus.http.proxy.proxy-address-forwarding=true` + `allow-forwarded=true` with a trusted-proxy allowlist once behind a reverse proxy.
  - For `/register`, never confirm/deny whether email exists. Return `202 Accepted` and send a mail branch from there.

#### `auth/resource/InternalSsoResource.java`

- **Purpose:** Serves a JWKS document at `/.well-known/jwks.json` read from a classpath resource.
- **Issues:**
  1. Reads `publicKey.json` from the classpath — meaning the JWKS is *baked into the jar*. Rotating keys requires a redeploy. Any other resource server that validates tokens against this JWKS must be coordinated.
  2. No `kid` rotation / multiple keys supported.
  3. `GET` returns raw JSON string built from `readAllBytes()` — fine but no `Cache-Control` header (clients may refetch each call).
  4. `@PermitAll` is implicit (no annotation means `@PermitAll` effectively for unauthenticated paths in Quarkus when `deny-by-default` is not on). This is correct for JWKS (must be public), but should be annotated explicitly for readability.
- **Improvements:** annotate `@PermitAll`; add `Cache-Control: public, max-age=3600`; move to a pluggable key-provider backed by Vault/SSM for zero-downtime rotation.

#### `auth/service/AuthService.java`

- **Purpose:** Core auth orchestration — register, login, refresh with reuse detection, logout, password reset, email verification, change-password, `getCurrentUser`.
- **Issues:**
  1. **`registerUser` sets `emailVerified=true` on creation** (line 93). That bypasses the entire email-verification flow for self-service readers. If this is intentional, the presence of `EmailVerificationToken` + `sendEmailVerification` flows is dead code; if not, this is a security bug (attackers register with someone else's email and the rightful owner finds the account already exists).
  2. **Login error message** (`UnauthorizedException("Invalid email or password")`, line 122) is used for both "unknown email" and "bad password" — good (no enumeration), but `handleFailedLogin(user, …)` only runs when the user exists, so timing side-channel still leaks existence (lookup + Argon2 verify for real users vs. cheap no-op for unknown emails). Consider a dummy-hash verify on unknown emails to equalize.
  3. **Refresh reuse detection is correct and a bright spot**: line 169-180 detects replay of a rotated token and revokes every session. Good.
  4. **`sessionService.findValidSessionByToken` is not `@Transactional`** — it performs a read on a repository. Ensure the call is inside a transactional outer method (it is, via `refreshToken`). OK.
  5. **Password-reset flow doesn't check if the user's status is LOCKED**. A locked user can reset their password and unlock themselves (line 283-284 resets `failedAttempts` and `lockedUntil`). That is actually the right behavior for a *temporary* lock but wrong for a *status=LOCKED* permanent lock — the service should refuse.
  6. `changePassword` (line 356) correctly verifies current password and revokes all sessions. It does **not** check for password strength against history (no password-reuse list) nor enforce a cooldown.
  7. **No breach/pwned-password check** for new passwords.
  8. **No password complexity validation in this service** — it delegates to DTO `@NotBlank`/`@Size`. Ensure the DTO enforces mixed-case/digit/special requirements (see `RegisterRequest` below).
  9. `resolveRoles` falls back to `Set.of(RoleName.USER.name())` if no role rows exist (line 408). That is a silent auto-elevation of any "role-less" user to USER. Prefer throwing.
  10. `parseUserType` accepts `String` from `RegisterRequest.getUserType()` — if null, throws NPE. Use the `UserType.valueOf` wrapper defensively.
  11. `toUserResponse` constructs the DTO via positional constructor — will break silently if field order changes.
  12. `resendVerificationEmail` returns early silently if already verified (line 346). Good — prevents enumeration.
  13. No rate-limit on `sendEmailVerificationToken` beyond the filter — a user who knows an email can rotate it through `/forgot-password` (with a reset token TTL). Fine given the filter, but noting for completeness.
- **Improvements:**
  - Flip `emailVerified=false` on register; gate login behind verification:
  ```java
  user.setEmailVerified(false);
  user.setStatus(UserStatus.PENDING_VERIFICATION);
  userRepository.save(user);
  sendEmailVerificationToken(user);
  ```
  - Equalize login timing:
  ```java
  private static final String DUMMY_HASH = "$argon2id$v=19$m=65536,t=2,p=1$…"; // generated once at startup
  if (userOpt.isEmpty()) {
      passwordService.verifyPassword(DUMMY_HASH, request.getPassword());
      throw new UnauthorizedException("Invalid email or password");
  }
  ```

#### `auth/service/JwtService.java`

- **Purpose:** Issues JWT access tokens and cryptographically-strong refresh tokens.
- **Issues:**
  1. Signs with `Jwt…sign()` — relies on `smallrye.jwt.sign.key.location` (RSA by default). Key algorithm is not pinned in code; if ops swaps to a symmetric key the token shape changes. Set `smallrye.jwt.sign.algorithm=RS256` explicitly in properties.
  2. TTL default is **15 min** (900s) from config — reasonable, but no refresh-TTL is exposed symmetrically (session expiry lives in `SessionService`).
  3. `SecureRandom` instance shared as instance field — fine.
  4. No `jti` (JWT ID) claim — if a future feature needs to revoke a specific access token before expiry, it can't be done.
  5. `generateRefreshToken` uses 32 bytes Base64-url — good, 256-bit entropy.
  6. **No `kid` claim** emitted — once key rotation is required, verifiers won't know which key to use.
- **Improvements:**
  ```java
  return Jwt.issuer(jwtIssuer)
      .subject(userId.toString())
      .upn(userId.toString())
      .groups(roles)
      .claim("userType", userType)
      .claim("sessionId", sessionId.toString())
      .claim("jti", UUID.randomUUID().toString())
      .expiresIn(getJwtTtlSeconds())
      .jws()
        .header("kid", currentKeyId)
      .sign();
  ```

#### `auth/service/SessionService.java`

- **Purpose:** Create/revoke sessions, look up session by refresh-token hash (both valid-only and any-state for reuse detection).
- **Issues:**
  1. **Refresh tokens are hashed with plain SHA-256** via `TokenHashUtil.sha256Base64`. If the DB is leaked, any attacker with the raw token would hash locally and find a match instantly. An HMAC with a server-side key (or `BCrypt`/Argon2 for this narrow use case) is stronger. SHA-256 is acceptable *if* combined with a per-install pepper.
  2. Unique-constraint collision handling is well-commented (line 43-53) — good.
  3. `revokeAllUserSessions` delegates to a repository method — make sure that method uses a bulk `UPDATE Session SET revoked=true WHERE user.id=?` rather than load-and-save in a loop (verify in repository).
  4. `findValidSessionByToken` silently reads through a revoked session and returns `Optional.empty()` — that is the correct behavior for refresh, but the resource layer won't distinguish "invalid token" from "revoked token" cases. (The `AuthService` separately calls `findAnySessionByToken` for replay detection — good.)
- **Improvements:**
  - Add a pepper: `sha256Base64(token + pepperFromConfig)`. The pepper lives outside the DB.
  - Prefer `HmacSHA256(pepper, token)` — constant-time and stronger misuse profile.

#### `auth/service/PasswordService.java`

- **Purpose:** Argon2id hashing wrapper.
- **Issues:**
  1. `Argon2Factory.create(ARGON2id, 16, 32)` sets salt=16 bytes, hash=32 bytes. OK.
  2. `argon2.hash(2, 65536, 1, password.toCharArray())` — parameters are `(iterations=2, memory=64MB, parallelism=1)`. **2 iterations is on the low end** for server-side Argon2id. OWASP ASVS 2023 recommends at least `m=64MB, t=3, p=1` or `m=19MB, t=2, p=1`; `m=64MB, t=3` is preferred. Tune against your p99 login latency.
  3. No `char[]`→zero pass after use — `password.toCharArray()` creates an array that sits in heap until GC. For defense-in-depth, null-fill after hash.
  4. Only a single Argon2 instance — OK.
- **Improvements:** `argon2.hash(3, 65536, 1, …)` or `argon2.hash(2, 19*1024, 1, …)` depending on your latency budget. Add `Argon2Helper.findIterations(...)` calibration on startup (the library supports this).

#### `auth/service/DeviceService.java`, `AuditService.java`

- **Purpose:** Track registered devices per user and append-only audit log rows.
- **Issues:** Not deeply read here — common pitfalls to verify:
  - Audit writes should be on a **separate transaction** (`REQUIRES_NEW`) so a business-logic rollback doesn't erase the audit breadcrumb.
  - `Device` rows are created on login — ensure uniqueness constraint on `(user_id, fingerprint)` to avoid duplicates.
- **Improvements:** Wrap `auditService.logEvent(...)` in a separate transaction via `@Transactional(TxType.REQUIRES_NEW)`; document the trade-off (event survives business rollback).

#### `auth/entity/Session.java`, `Device.java`, `AuditLog.java`, `EmailVerificationToken.java`, `PasswordResetToken.java`, `Role.java`, `UserRole.java`

- **Purpose:** Persistence for auth concerns.
- **Issues (common):**
  1. All entities inherit `BaseEntity` — consistent and correct.
  2. Watch Lombok `@Getter`/`@Setter` on entities with `@OneToMany` back-references — `@ToString`/`@EqualsAndHashCode` are NOT applied (`BaseEntity` overrides equals/hashCode correctly). Good.
  3. `Session.refreshTokenHash` needs a unique constraint (declared via the comment `uk_session_refresh_token_hash`) — ensure the JPA annotation matches: `@Column(unique=true)` or `@Table(uniqueConstraints=…)`.
  4. `EmailVerificationToken` / `PasswordResetToken` should have indexes on `tokenHash` (lookup) and on `expiresAt` (cleanup scheduler).
  5. `Role` enum-backed table with natural key — ensure `@Enumerated(EnumType.STRING)` and a unique constraint on `name`.
  6. `UserRole` is a join table — verify it has a composite unique on `(user_id, role_id)` to prevent duplicate grants.
- **Improvements:** audit indexes and constraints during DB migration design.

#### `auth/dto/*`

- **Purpose:** Request/response DTOs.
- **Issues:**
  1. **Password complexity validation is mostly `@NotBlank` + `@Size`** — no regex for character-class requirements. NIST SP 800-63B permits relaxed complexity in favor of length + breach-check, so this is defensible *if* you add a breach-check (HIBP API). Currently neither is done.
  2. `LoginRequest.deviceFingerprint` is optional and untyped — accept any string; if you want reliable device-tracking, validate format.
  3. `RegisterRequest.userType` is a raw `String` rather than the `UserType` enum, so valid-values drift is only caught at service level. Prefer `@Pattern(regexp = "^(READER|AUTHOR)$")` or bind to the enum.
  4. `ForgotPasswordRequest.email` should have `@Email`.
  5. `ResetPasswordRequest.token` should have `@NotBlank` + `@Size(max=…)`.
  6. `VerifyEmailRequest.token` — same.
  7. No DTO captures `User-Agent` / `X-Device-Id` explicitly; the resource reads header directly.
- **Improvements:**
  ```java
  public class RegisterRequest {
      @Email @NotBlank @Size(max = 254) private String email;
      @NotBlank @Size(min = 12, max = 128)
      @Pattern(regexp = ".*[A-Z].*", message = "must contain an uppercase letter")
      @Pattern(regexp = ".*[0-9].*", message = "must contain a digit")
      private String password;
      @NotBlank @Size(max=80) private String firstName;
      @NotBlank @Size(max=80) private String lastName;
      @Pattern(regexp = "^(READER)$", message = "only READER self-registration allowed") // authors created by admin
      private String userType;
  }
  ```

#### `auth/repository/*`

- **Purpose:** Panache repositories for the auth entities.
- **Issues (common):**
  1. Custom methods such as `findByEmail`, `findRoleNamesByUserId`, `findByTokenHash`, `revokeAllUserSessions`, `deleteExpiredOrRevokedBefore` need to be verified for parameterized queries (no string concatenation). Panache `find("email", email)` is safe; `find("email = ?1", email)` too. `UserRoleRepository#findRoleNamesByUserId` likely returns a `List<RoleName>` via a projection query — verify it uses a JPQL `select ur.role.name from UserRole ur where ur.user.id = ?1` and test the implicit join.
  2. Missing `session.user.id` index — would be automatic via FK, but confirm at DB layer.
- **Improvements:** write integration tests for each repository method; enable `quarkus.hibernate-orm.log.sql=true` in dev to spot N+1.

#### `auth/enums/*`

- Simple enums (`EventType`, `RoleName`, `UserStatus`, `UserType`). Low risk.
- **Improvements:** add Javadoc and a central enum-to-DB-width mapping guide.

---

### 3.3 Catalog Module

#### `catalog/entity/Book.java`

- **Purpose:** Core book entity with approval workflow + price/discount.
- **Issues:**
  1. `price` and `discount` are `BigDecimal(10,2)` — ceiling of `99,999,999.99`. Fine for a book marketplace. OK.
  2. `@Check` DB-level invariant on discount ≤ price — good, layered defense.
  3. `@Version` for optimistic locking — correct. The service must handle `OptimisticLockException` on admin race; currently it doesn't.
  4. `is_published` is a `boolean` column — keep in mind that filtering by *status=APPROVED AND is_published=true* is always redundant (APPROVED always sets `isPublished=true` in `BookService.approveBook`). Consider dropping one.
  5. `ManyToOne(fetch=LAZY)` on `category` and `author` — correct, but every `toResponse(book)` dereferences `book.getCategory().getName()` and `book.getAuthor().getId()` → triggers a lazy load. See N+1 below.
  6. `rejectionReason` has no length cap — could accept a book-length essay; bound with `@Column(length=500)`.
  7. No full-text search index on `title`/`keywords`/`description` — future search feature will be slow.
  8. `Index` on `is_published` alone is low-cardinality (two values); the query planner may ignore it. A composite `(status, is_published, published_date)` is more useful.
- **Improvements:**
  ```java
  @Column(name = "rejection_reason", length = 500)
  private String rejectionReason;

  @Table(name = "m_books", indexes = {
      @Index(name = "idx_book_status_published_date", columnList = "status, is_published, published_date DESC"),
      @Index(name = "idx_book_author", columnList = "author_id"),
      @Index(name = "idx_book_category", columnList = "category_id")
  })
  ```

#### `catalog/entity/BookApprovalLog.java`, `Category.java`

- `BookApprovalLog` — append-only event log. Ensure `createdAt` index for timeline queries and `book_id` index for the history endpoint.
- `Category` — standard master table; `slug` should be `@Column(unique=true)`.

#### `catalog/resource/BookResource.java`, `CategoryResource.java`, `AuthorPublicResource.java`

- **Purpose:** Author CRUD on own books; admin-facing read-listings are duplicated at `/admin/books`; public listings for category/author.
- **Issues:**
  1. Author write-endpoints (`POST /books`, `PUT /books/{id}`, `DELETE /books/{id}`) must be role-gated. Verify each has `@RolesAllowed({"USER"})` **and** the service performs an `UserType==AUTHOR` check — `BookService.findAuthorOrThrow` does, good.
  2. Pagination is optional in many endpoints (falls back to `getAllBooks()` without paging). Unbounded list responses are a DoS vector on large catalogs. Enforce pagination.
  3. `ApiResponse<List<…>>` shape inconsistent with `ApiResponse<PagedResponse<…>>` — frontend must branch. Pick one.
- **Improvements:** require `page`/`size` query params with defaults (page=0, size=20) and drop the non-paged variants.

#### `catalog/service/BookService.java`

- **Purpose:** Author submit/update/delete, admin approve/reject, pagination, approval history, public reads.
- **Issues:**
  1. **Hardcoded admin lookup by email** (line 381: `taskt600@gmail.com`) in `findAdminUser()`. That is the same email the seeder uses. Moving environments or renaming the admin breaks every book submission because `BookApprovalLog.sender`/`receiver` won't nullably resolve. Drive the receiver from the `ADMIN` role membership (`userRoleRepository.findUsersByRole(ADMIN).stream().findAny()`).
  2. **Cosmetic-vs-substantive update detection** (line 114-120): misses `discount`, `keywords`, `pages`, `coverUrl`, `versionNumber` — any of which can be a content change requiring re-review. Either re-queue on any update or expand the diff list.
  3. `fileKeyToUrl`/`urlToFileKey` (line 521-548) string-parses URLs using `publicFileBaseUrl` — works for current shape but fragile. Store the bare key in the API contract and derive the URL only on output.
  4. `@Transactional` on **every read path** (e.g. `getPublishedBooks`, `getMyBooks`) — strictly needed for lazy-load but means read-only methods still get a write-transaction. Use `@Transactional(TxType.SUPPORTS)` or declaratively `@Transactional` with `readOnly=true` semantics via `TransactionAttribute` — although in practice for Panache with JTA it is acceptable.
  5. **N+1 on category**: `toResponse(book)` accesses `book.getCategory().getName()` — because the book is lazy-loaded per row without `JOIN FETCH`. Paginated listings would issue N category queries. Check `bookRepository.findAllWithDetailsPage` / `findPublishedPage` — need to `JOIN FETCH category, author`.
  6. **`batchAuthorNames` already fixes the author N+1** — good. Do the same for `category`.
  7. `deleteBook` soft-deletes by setting `BookStatus.DELETED` — but `FileResource` uses `fileKey` to serve downloads. A DELETED book's file still exists on disk. Add a lifecycle hook to call `storageService.delete(fileKey)` once approval history is preserved.
  8. `approveBook` sets `publishedDate = Instant.now()` even on re-approvals — you may want to preserve the original publication date. Consider `if (book.getPublishedDate() == null)`.
  9. **Rate limit** on `/books` POST is covered by filter (`BOOKS_WRITE_MAX_REQUESTS=30/min/ip`); per-author limit is not enforced, so one IP with many author tokens could spam.
- **Improvements:**
  ```java
  private User findAdminUser() {
      List<User> admins = userRoleRepository.findUsersByRole(RoleName.ADMIN);
      if (admins.isEmpty())
          throw new ResourceNotFoundException("No admin configured — run the seeder");
      return admins.get(0);
  }
  ```

#### `catalog/service/CategoryService.java`, `catalog/repository/*`, `catalog/dto/*`

- **CategoryService** — likely straightforward CRUD; verify it enforces admin-only writes and rejects deactivation when books are still bound to the category.
- **Repositories** — all Panache. `BookRepository` exports `SORTABLE_FIELDS` + `DEFAULT_SORT_FIELD` (referenced from `AdminResource:154`). Sorting is allowlist-based — good, prevents SQL injection via `sort=; DROP TABLE`.
- **DTOs** — `CreateBookRequest`/`UpdateBookRequest` must validate `price >= 0`, `discount >= 0 && discount <= price` (the `@Check` is layered but user-facing messages come from bean validation). Verify `@Valid` + `@AssertTrue` on a custom method.

---

### 3.4 Commerce Module

#### `commerce/entity/CartItem.java`, `PaymentHistory.java`, `PaymentTransaction.java`

- **CartItem** — verify unique on `(user_id, book_id)` to match service-side `findByUserAndBook` semantics; without it a race can create duplicates.
- **PaymentHistory** — `externalPaymentId` must be unique per `(user_id, externalPaymentId)` to back the idempotency-key replay; the service already catches the constraint-violation race, so the constraint must exist.
- **PaymentTransaction** — line-item table; must have `payment_history_id` + `book_id` indexed.
- **Amounts** use `BigDecimal` throughout — good.

#### `commerce/resource/CartResource.java`, `PaymentResource.java`

- Correctly use `jwt.getSubject()` as the user identity (no IDOR via request-body `userId`).
- `PaymentResource.checkout` is `@RolesAllowed({"USER","ADMIN"})` — fine.
- Missing `@Valid` on `AddToCartRequest` check — verify.
- `getMyPayments` supports pagination with sort allowlist (`PaymentHistoryRepository.SORTABLE_FIELDS`) — good.

#### `commerce/service/PaymentService.java`

- **Purpose:** Mock payment orchestration with price snapshots, idempotency, cart cleanup, ledger reconciliation.
- **Issues:**
  1. **Price snapshotting is correct** — `priceSnapshots` map holds the exact numbers used across the transaction; ledger reconciliation (line 193-196) verifies the sum. Excellent.
  2. **Idempotency-key derivation** from `(userId + sorted bookIds)` (line 204-208) — reasonable fallback. A client resubmitting the same cart won't double-charge. Just noting: if the user mutates their cart after one request fails, the derived key differs — could still double-fire if both attempts hit the service before the first commits.
  3. **Concurrent-checkout race is handled** via unique `(user_id, externalPaymentId)` constraint + 409 retry hint (line 148-154). Good.
  4. **No real payment gateway** — `PAYMENT_METHOD = "MOCK"`, `PaymentStatus.SUCCESS` is set before any external call. Fine for dev; make sure the production gateway integration preserves idempotency semantics.
  5. `@Transactional` on `getUserPayments`, `getPurchasedBooks` — OK for lazy-load but again read-only. Consider `readOnly`.
  6. **N+1 risk on `getUserPayment`** — fetches items for a single payment, then iterates their `book.getCategory().getName()` / `book.getAuthor()`; the service does `batchAuthorNames(books)` (good) but categories are still per-book.
  7. **Author-name lookup path** duplicated across `BookService`, `CartService`, `PaymentService` — extract to a shared helper.
  8. `effectivePrice` guards against negative values (line 127, 314, 339) — good. Same safety nets in UserBook response.
  9. **`getPurchasedBooks` uses `Panache Page.of(…).list()`** — correct usage. `Sort` allowlist is via `PageRequest.parse` + `SORTABLE_FIELDS`.
  10. `bookId.toString()`-keyed duplicates: `request.getBookIds()` is not deduped in-service. Duplicate book IDs from a malformed client would hit the `userBookRepository.existsByUserAndBook` check on the second pass — would throw `ConflictException`. OK, but consider `new LinkedHashSet<>(request.getBookIds())` at the top for cleaner errors.
- **Improvements:**
  - Extract `AuthorNameResolver` as a `@ApplicationScoped` helper.
  - Add a `@ReadOnly`-ish marker method: `@Transactional(value=TxType.SUPPORTS)` for read paths on services that use Panache for lazy loading.
  - Deduplicate book IDs on checkout ingestion.

#### `commerce/service/CartService.java`

- **Purpose:** Cart CRUD + one-shot checkout delegating to `PaymentService`.
- **Issues:**
  1. `addToCart` handles three branches (ownership, duplicate cart, blocking own book) — explicit and clear.
  2. `getCart` filters stale items at read time — **good**, but does not persist the cleanup (the `clearCart`-style cleanup happens only on `checkout`). A cart can stay stale forever if the user never checks out.
  3. `checkout` deletes stale items individually (`cartItemRepository.deleteByUserAndBook`) in a loop — fine at low item counts; for large carts use `deleteByUserAndBookIds`.
  4. `calculateEffectivePrice` is duplicated from `PaymentService.effectivePrice` — centralize.
  5. `findUserOrThrow` looks up the user but the user is already authenticated — you have the UUID. The lookup is needed only to attach the entity in `addToCart`, but a `getReference(userId)` via the EntityManager would avoid the extra SELECT.
- **Improvements:** introduce a `PriceCalculator` utility; use `EntityManager.getReference(User.class, userId)` in write paths where the entity is only needed for FK attachment.

#### `commerce/repository/*`, `commerce/dto/*`, `commerce/enums/PaymentStatus.java`

- **Repos** — `CartItemRepository.deleteByUserAndBook` must use a parameterized DELETE. `PaymentHistoryRepository.findByUserAndIdempotencyKey` must be unique-indexed.
- **DTOs** — `CheckoutRequest` should validate `bookIds != null && !bookIds.isEmpty() && size <= 50` etc.
- **PaymentStatus enum** — verify `@Enumerated(EnumType.STRING)` on the field.

---

### 3.5 User Module

#### `user/entity/User.java`

- **Purpose:** Core user account.
- **Issues:**
  1. **No explicit `@Index` on `email`** aside from the implicit unique index — fine, Postgres will build one for `UNIQUE`.
  2. **`@OneToMany(cascade = REMOVE, orphanRemoval = true)` on `userRoles`, `sessions`, `devices`** — deleting a User wipes them. Note that many other child entities (`UserBook`, `CartItem`, `AuditLog`, `PaymentHistory`, `EmailVerificationToken`, `PasswordResetToken`, `BookApprovalLog.sender/receiver`) are **not** mapped here and will not cascade. A naïve `userRepository.delete(user)` will FK-fail. There is no documented `deleteUser` service; don't add one without planning the full deletion path (and GDPR considerations).
  3. No `@Version` for optimistic locking — concurrent updates to `failedAttempts`/`lockedUntil` can race. Under high-concurrency login-floods, lost updates can *under-count* failures.
  4. `password_hash` has no length constraint — Argon2id outputs variable-length strings (~100 chars for default params). Set `length = 200` to be safe.
  5. `email` has no length constraint — default varchar(255) is fine but make explicit.
- **Improvements:**
  ```java
  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @JsonIgnore
  @Column(name = "password_hash", nullable = false, length = 200)
  private String passwordHash;

  @Version
  @Column(name = "version")
  private Long version;
  ```

#### `user/entity/UserProfile.java`, `UserBook.java`, `RecentBook.java`, `BookAccessLog.java`

- **UserProfile** — one-to-one with User; validate `firstName`/`lastName` length; avatar URL should be a validated key (not a free-text URL).
- **UserBook** — entitlement table; **must have unique on `(user_id, book_id)`** to back `existsByUserAndBook`.
- **RecentBook** / **BookAccessLog** — access-log tables; partition or archive-after-30-days, otherwise these grow unboundedly.

#### `user/resource/UserProfileResource.java`, `user/service/UserProfileService.java`

- **Issues (likely):**
  1. `GET /user/profile` / `PUT /user/profile` — verify both use `jwt.getSubject()` rather than accepting a userId parameter (IDOR risk).
  2. Profile update must whitelist fields (no mass-assignment). Don't `BeanUtils.copyProperties(request, profile)`.
  3. Avatar upload should flow through `UploadResource` (kind=profile) and write a `fileKey`, not a public URL.
  4. Email in the profile should not be mutable here — otherwise an attacker with an access token can change their email and hijack password recovery.

#### `user/repository/UserBookRepository.java`, `UserProfileRepository.java`, `user/dto/*`

- **Repos** — `findByUserIds(Set<UUID>)` used for batch author-name lookup; ensure it's a single `WHERE user_id IN (:ids)` query.
- **DTOs** — `UpdateProfileRequest` must have `@Size`/`@Pattern` on every field.

---

### 3.6 Admin Module

#### `admin/resource/AdminResource.java`

- **Purpose:** Author CRUD, book approvals, seeder trigger.
- **Issues:**
  1. **`/admin/seed` is `@PermitAll`** (line 59), authenticated only by `X-Seed-Secret` header compared against `app.seed-secret` (default `ebookhub-seed-secret-2026`). If the env var is not set, anyone who can read the repo can take over the system. **P0.**
  2. Seeder result messages disclose "already seeded" vs "seeded successfully" — reveals system state to an unauth caller that guesses the secret.
  3. `createAuthor` immediately sends verification email containing author credentials — ensure `AdminAuthorService.createAuthor` generates a strong temporary password and does NOT log it in plaintext (audit ban). Looking at `EmailService.sendAuthorCredentials` — **the raw password is emailed in the clear** (line 73-100). Mitigate by requiring the author to set a password via the reset-password flow on first login, rather than emailing a plaintext credential. SMTP transport is HTTPS/TLS but the email sits in recipient inboxes indefinitely.
  4. `deleteAuthor` only deactivates — should document explicitly and ensure the author's published books remain purchasable (don't cascade-unpublish; commerce state must not depend on author state).
- **Improvements:**
  - Remove the endpoint. Run the seeder via `@Observes StartupEvent` gated on `quarkus.profile=dev || ${APP_BOOTSTRAP_ENABLED:false}`.
  - If the endpoint must stay, gate it with `@RolesAllowed("ADMIN")` AND the header secret AND a 5-minute TTL (`seededAt > now()-5min`).
  - Replace "email a password" with "email a one-time setup link that routes to `/auth/reset-password`".

#### `admin/service/AdminSeederService.java`

- **Purpose:** One-time master-data + admin-user bootstrap.
- **Issues:**
  1. **`DEFAULT_ADMIN_PASSWORD = "Admin@12345"` (line 37)** — compiled into the JAR, logged at INFO (line 191). Grep for "Admin@" would turn up thousands of these. **P0.**
  2. The admin is seeded with `UserType.READER` and both `ADMIN` + `USER` roles (line 169, 176, 181). Elsewhere `BookService.findAdminUser()` hardcodes the admin *email*; coupling the seeder email to the service code means renaming the admin breaks the book flow. Drive admin resolution from role, not email.
  3. `seedCategories` seeds only `Pulmonology` — likely reflective of a domain niche; make sure the deployment targets are aligned (project is called "ebook" but categories are medical).
  4. `markAsSeeded` calls `configParamRepository.persist(...)` while the rest of the class uses `save(...)` — inconsistent naming masks whether persist is an upsert or an insert.
  5. `seed()` uses a single `@Transactional` and calls `configService.refreshCache()` inside the TX — if the cache refresh throws, the seed rolls back entirely. Move cache refresh after TX commit.
  6. `findByEmail(DEFAULT_ADMIN_EMAIL).isPresent()` makes the seeder idempotent for admin creation, but a second invocation still re-seeds roles/categories/config — that's guarded by `isAlreadySeeded()`, OK.
- **Improvements:**
  ```java
  private static final String DEFAULT_ADMIN_EMAIL =
      ConfigProvider.getConfig().getValue("app.admin-email", String.class);

  private String generatedPassword;

  private boolean seedAdminUser() {
      if (userRepository.findByEmail(DEFAULT_ADMIN_EMAIL).isPresent()) return false;
      this.generatedPassword = PasswordGenerator.generate(24);
      // … create user …
      LOG.info("Seeder: admin created — password written to one-time bootstrap file");
      Files.writeString(Path.of("/run/secrets/admin-bootstrap-password"),
                        this.generatedPassword,
                        StandardOpenOption.CREATE_NEW);
      return true;
  }
  ```

#### `admin/service/AdminAuthorService.java`, `admin/dto/*`

- **Issues (likely):**
  1. Creating an AUTHOR should mint a strong random password (use `PasswordGenerator`), store only the hash, and email a one-time setup link. Verify.
  2. `UpdateAuthorRequest` — must not let admins change another user's password directly via this flow (that's a separate audited path).
  3. `deactivate` / `toggle` — ensure audit trail.
  4. `resendVerification` — idempotent? Rate-limited?

---

### 3.7 Common Module

#### `common/dto/ApiResponse.java`, `ErrorResponse.java`, `PagedResponse.java`, `PageRequest.java`

- **ApiResponse** — generic envelope. Includes `success`, `message`, `data`. Simple and consistent.
  - Missing `timestamp` field (ordering on client) and OpenAPI `@Schema` annotations.
- **ErrorResponse** — status, error code, message. Missing `path`, `timestamp`, `details`.
- **PagedResponse** / **PageRequest** — static factory `PageRequest.parse(page, size, sort, allowlist, defaultField)` returning null when params absent. That dual API (paged vs non-paged) propagates branching into every resource. Prefer always-paged with defaults (page=0, size=20).
- **PageRequest** — the sort allowlist defense (`Set<String> allowedFields`) is a strong guardrail. Good.

#### `common/entity/BaseEntity.java`

- **Purpose:** UUID + createdAt/updatedAt + JPA equals/hashCode.
- **Issues:** `equals` uses id-based equality with null-safety (line 38-45); `hashCode()` returns `getClass().hashCode()` — correct approach for entities with late-assigned IDs. Good.
- **Improvement:** make fields `@Column(updatable=false)` only for `createdAt` (already done) and consider switching to Hibernate `@CreationTimestamp` / `@UpdateTimestamp` to delegate to the framework, but manual setters are fine.

#### `common/entity/ConfigParam.java`

- Runtime-tweakable config backed by DB. Ensure a unique key constraint and that the cache refresh is atomic.

#### `common/exception/*`

- Well-structured hierarchy: `ApplicationException` with `getHttpStatus()` + `getErrorCode()`; subclasses for 400/401/403/404/405/409/500/validation.
- **GlobalExceptionHandler.java** — comprehensive mapping (see line-by-line):
  - Maps `ApplicationException`, Bean Validation, JPA constraint violations, `IllegalArgumentException`, `AuthenticationFailedException`, JAX-RS `NotAuthorized`/`Forbidden`/`NotFound`/`NotAllowed`/`NotSupported`, any `WebApplicationException`, else 500.
  - **Issues:**
    1. Message strings returned for 5xx can leak internals: the 500 fallback returns "An unexpected error occurred. Please try again later." — good. But note the mapper logs `exception.getMessage()` with stack trace only on 5xx; make sure logs never ship to clients.
    2. `ConstraintViolationException` message concatenation (line 38-40) emits `propertyPath: message; propertyPath: message;` — fine for API but consider returning a structured `violations: [...]` array in the body.
    3. No mapping for `org.jboss.resteasy.reactive.ClientWebApplicationException` (rare).
    4. The generic 500 handler will also swallow `UnsatisfiedResolutionException` from CDI bootstrap errors — developer-unfriendly. Log level OK but consider a specific branch for `RuntimeException` vs `Error`.
    5. No `OptimisticLockException` mapping — if two admins approve simultaneously, the second gets a generic 500 (goes through the `PersistenceException` arm and returns 409 — actually OK, but message is misleading).
- **Improvements:** return structured validation errors; add explicit branches for `OptimisticLockException` → 409.

#### `common/filter/RateLimitFilter.java`

- **Purpose:** Per-IP + per-bucket sliding-window rate limit.
- **Issues:**
  1. **In-memory `ConcurrentHashMap`** — does not survive restarts; does not scale horizontally. Two Quarkus pods = 2× the quota per IP.
  2. **Fail-open design** (line 52-79) — if the filter throws, requests pass. Deliberate (correct for a broken limiter), but any silent failure never alerts anyone because the exception is only logged. Add a `Counter` metric on `rate_limit.filter.error` so a dashboard catches it.
  3. `cleanupStaleEntries` only triggers at `> 10_000` entries (line 127) — a memory-pressure watermark. A scheduled cleanup would be more predictable.
  4. `WINDOW_MILLIS = 60_000` hardcoded — externalize.
  5. Uses `X-Forwarded-For` via `ClientIpResolver` — which itself defaults to `trust=true`. Same spoofing concerns carry here.
  6. Path matching uses `contains()` — `path.contains("/books/")` will match `/books/evil/books/` if cleverly crafted. Use a `startsWith` + end-of-segment discipline.
  7. No `Retry-After` header that varies — always `60`. The actual reset time is `windowStart + WINDOW_MILLIS`, which is known.
  8. `resolveRule` returns `null` for read paths outside `/auth` — unbounded. A single GET endpoint can be DOSed.
  9. `RateLimitBucket.incrementAndCheck` uses CAS for window rollover (good), but `count.incrementAndGet()` racing the CAS means a burst right at the boundary could over-count by a few (minor).
  10. **No per-user rate limit** — authenticated users share the filter with unauthenticated ones by IP. One authenticated user behind a NAT/mobile carrier can exhaust the IP quota for everyone on that NAT.
- **Improvements:** use Quarkus's `@RateLimited` (smallrye-fault-tolerance) for declarative per-user limits, back the per-IP limiter with Redis for distributed fairness.

#### `common/repository/BaseRepository.java`, `ConfigParamRepository.java`

- BaseRepository likely a thin Panache wrapper. ConfigParam lookup by key must be case-sensitive and unique-indexed.

#### `common/resource/ConfigResource.java`

- **Issue (likely):** exposes runtime config parameters. Verify `@RolesAllowed("ADMIN")` on writes; ensure secrets (passwords, API keys) are never stored in `ConfigParam` (this is a user-visible config table).

#### `common/resource/HealthResource.java`

- Always returns `UP` — no dependency checks. Quarkus `smallrye-health` is available at `/q/health/ready` and `/q/health/live` but is not split into liveness vs readiness explicitly. Expose readiness that checks DB + mail + storage, liveness that only checks the process.
- `@PermitAll` — correct.

#### `common/scheduler/TokenCleanupScheduler.java`

- **Purpose:** Hourly cleanup of expired sessions + tokens.
- **Issues:**
  1. `@Scheduled(every = "1h")` — runs on every node in a cluster. Either use `concurrentExecution=SKIP` (does nothing for multi-node) or integrate with `quarkus-scheduler`'s `@Scheduled(identity="...")` + a clustered lock.
  2. Per-call try/catch (`safeDelete`) is well-reasoned (line 38-39).
  3. 30-day session threshold + 7-day token threshold — document these in config params for tunability.
  4. `LOG.infof` on zero-work runs is suppressed (good).
- **Improvements:** introduce a `DatabaseLock` via Postgres advisory locks (`pg_try_advisory_lock`) to make the job cluster-safe; externalize thresholds via `ConfigService`.

#### `common/service/ConfigService.java`

- Runtime-loaded config with cache refresh — verify that the cache is a `ConcurrentHashMap` re-hydrated within a transaction, and that `refreshCache()` is idempotent.

#### `common/service/EmailService.java`

- **Purpose:** HTML email for verification, reset, author credentials, approval notifications.
- **Issues:**
  1. **Synchronous blocking** `mailer.send(Mail.withHtml(to, subject, html))` on the request thread. SMTP to Gmail = 0.2–2 s typical; a transient hiccup tail-latencies out. Quarkus REST uses worker threads (OK), but you still burn a thread and the request waits.
  2. **Failure is swallowed** (line 144): `LOG.errorf(...)` then the method returns normally. Verification and reset mails that never arrive result in users blocked with no signal to ops.
  3. **No retries.**
  4. **HTML is built via `String.formatted(...)`** — if `rawToken` / `rawPassword` ever contained `%`/`"` characters, the output mis-formats. Low risk given token format, but use a templating engine (Qute is bundled with Quarkus) for safety.
  5. **Email-injection risk:** `toEmail` goes directly into `Mail.withHtml(to, …)`. If `to` contained `\r\n`, Quarkus's SMTP client should reject it, but validate at the DTO/service boundary.
  6. **`sendAuthorCredentials` emails a plaintext password.** Discussed in `AdminResource`. Replace with one-time-setup link.
  7. **Sender / admin addresses fall back to hardcoded values** (`taskt600@gmail.com`, `isms24analytics@gmail.com`). Remove defaults.
  8. **No DKIM/SPF mention** — relies on Gmail's defaults. Ensure production emails are sent from a domain you control with SPF/DKIM aligned.
- **Improvements:** switch to `ReactiveMailer` + `Uni.runSubscriptionOn(workerPool)` or an explicit `ManagedExecutor` with a bounded queue and retries; convert the HTML to Qute templates.

#### `common/storage/FileStorageService.java`, `LocalFileStorageService.java`, `StoredFile.java`, `UploadKind.java`

- **FileStorageService** — interface defining `store`/`load`/`delete`/`resolveLocalPath`.
- **LocalFileStorageService** — `@LookupIfProperty("storage.provider", "local", lookupIfMissing=true)` — good, allows swapping to S3 later.
  - **Issues:**
    1. `Paths.get(baseDir).toAbsolutePath().normalize()` and downstream `target.startsWith(baseDirPath)` — defense against path traversal. Correct.
    2. **MIME type trust:** `contentType` is whatever the client claims via `file.contentType()`. No magic-byte sniffing. An attacker can upload `evil.exe` with `Content-Type: application/pdf` and it'll be accepted by the `BOOK` kind. Add at least a `java.net.URLConnection.guessContentTypeFromStream(...)` cross-check.
    3. **Filename extension trust:** `extractExtension` picks from the original filename; if missing, falls back to content-type. A pathological user could upload `shell.sh` with `content-type=application/pdf` — output key gets `.sh`. Not served as PDF by the browser, but a mismatch.
    4. **Public URL construction** uses `backendBaseUrl` defaulting to `https://pulmo-be.onrender.com` — a hardcoded third-party default. If the env var is not set, URLs break silently.
    5. No virus scanning. For a marketplace shipping PDFs/EPUBs, consider clamav integration via a sidecar.
    6. `Files.probeContentType` on load — platform dependent, can return `null` for EPUB. Store the content-type alongside the key in a lookup table to avoid this.
    7. **Base dir `/tmp/uploads`** — non-persistent in containers. **P1.**
    8. No LRU/size cap — an open endpoint can fill disk.
- **UploadKind** — solid, self-contained, correct size + MIME whitelisting.
- **Improvements:** add magic-byte check; persist `StoredFile` metadata (key → content-type) to a `m_file_asset` table; use an S3-backed implementation for prod (already scaffolded).

#### `common/storage/UploadResource.java`, `UploadResponse.java`

- **Issues:**
  1. `@RolesAllowed({"USER","ADMIN"})` — any authenticated user can upload a `BOOK`-kind file (100 MB). No author check. An attacker with a reader account can burn 100 MB of disk per upload. At the very least, gate `BOOK` kind to authors.
  2. Doesn't enforce a **per-user upload quota**.
  3. `file.size() <= 0` is the only size lower bound; empty truncation attacks can produce tiny files that are then referenced from a `Book.fileKey` and served.
- **Improvements:** per-kind role gate, per-user quota via `ConfigParam`, reject files smaller than kind-specific minima.

#### `common/storage/FileResource.java`

- **Purpose:** Streams stored files. Public for cover/preview/profile; owner-only for book.
- **Issues:**
  1. **`@PermitAll` at class level + dynamic enforcement via `SecurityContext` and `JsonWebToken`** — correct, but `enforceBookAccess` uses `securityContext.isUserInRole("USER") || ADMIN`. If the request has a *valid but expired* JWT, Quarkus may reject before reaching here; if no JWT is present, `SecurityContext` roles are empty → throws 403. OK.
  2. `bookRepository.find("fileKey", key).firstResult()` — **fileKey is not indexed** at the entity level (see `Book` entity). A file fetch on a large book table scans linearly. Add `@Index` on `file_key`.
  3. No `ETag` / conditional GET support — clients re-download the same file every time.
  4. No range-request support (HTTP 206) — large PDFs / EPUBs can't be resumed.
  5. `StreamingOutput` lambda closes `stream` — good (line 79-82).
  6. Content-Type comes from `storageService.load` — can be `application/octet-stream` fallback, which forces the browser to download instead of inline-render for known types.
- **Improvements:** add `@Index` on `file_key`, implement `If-None-Match`/`If-Modified-Since`, support `Range` for the `BOOK` kind.

#### `common/util/ClientIpResolver.java`

- **Purpose:** Parse & validate `X-Forwarded-For`; gate behind `trustForwardedFor` flag.
- **Issues:**
  1. Default trust = **true** at every caller — defeats the guard unless ops flips it.
  2. Takes only the first IP (`split(",", 2)[0].trim()`) — correct semantics (leftmost = original client) **only if** your reverse proxy appends and you control it. If multiple proxies, the leftmost is attacker-controlled.
  3. Pragmatic IPv4/IPv6 regex is OK for log/audit use. Not RFC-complete.
- **Improvements:** configuration should supply a trusted-proxy CIDR list; only trust `X-Forwarded-For` when the immediate peer is in that list (Quarkus has `quarkus.http.proxy.trusted-proxies` for this).

#### `common/util/PasswordGenerator.java`, `MetadataUtil.java`, `TokenHashUtil.java`

- **PasswordGenerator** — verify it uses `SecureRandom` and includes all required character classes.
- **MetadataUtil.build(k, v, ...)** — used all over for audit metadata. Confirm it returns a stable JSON string (audit queries should be able to index it as JSONB).
- **TokenHashUtil** — plain SHA-256 hex/base64. **Consider HMAC-SHA256 with a server pepper** as discussed under `SessionService`. `generateSecureToken` is correct (32-byte URL-safe Base64 without padding).

---

## 4. Cross-Cutting Concerns

### 4.1 Logging

- **Uses:** `org.jboss.logging.Logger` throughout — consistent.
- **Good:** log-forgery attack surface is low (no direct user input in log strings without at least format-specifier safety); log levels are appropriate (DEBUG for suppressed enumeration cases, WARN for security events, INFO for lifecycle).
- **Bad:**
  - No **correlation-ID / trace-ID** — can't trace a request from the access log through service logs.
  - No **JSON structured logs** — `quarkus-logging-json` is not enabled; console logs are text.
  - **Sensitive fields logged inline:** `LOG.infof("Book %s removed from cart for user %s", bookId, userId)` is fine; `LOG.infof("Attempting SMTP send to=%s, subject=%s", to, subject)` (EmailService) is borderline — subjects are safe but never log token values. Search for any `LOG.*token*` — AuthService line 264 logs `tokenId`, OK.
  - `AdminSeederService` logs the plaintext admin password at INFO (line 191) — **P0**.
- **Fix:**
  ```properties
  quarkus.log.console.json=true
  quarkus.log.console.json.additional-field."service".value=ebook-be
  %prod.quarkus.log.level=INFO
  %prod.quarkus.log.category."com.ebook".level=INFO
  ```

### 4.2 Security

| Concern | Current state | Action |
|--------|--------------|--------|
| Password hashing | Argon2id, t=2 m=64MB — acceptable, low side | Bump to t=3 |
| JWT | RS256 (implicit), no `kid`, 15-min TTL | Add `kid` + JWKS rotation; separate RS256 explicit |
| Refresh tokens | SHA-256 hashed at rest; reuse detection → global revoke | Add server pepper |
| Sessions | Revocable; global logout works | Add device-trust scoring |
| CSRF | Relies on JWT in `Authorization` | Ban cookie storage or add CSRF tokens if cookies |
| CORS | `*` with credentialed Authorization | Lock to frontend origin |
| Rate limiting | In-memory, per-IP, fail-open | Redis-backed, per-user + per-IP |
| Input validation | Bean Validation on DTOs, allowlist sort | Complete password-complexity rules |
| File uploads | MIME allowlist + size caps + path-traversal guards | Add magic-byte check + quota |
| IDOR | `jwt.getSubject()` everywhere | Keep enforcing |
| Seeder | HTTP endpoint with weak default secret + plaintext admin password | Remove from HTTP, generate password |
| Secrets | Env vars used, but SMTP password & seed secret have defaults | Remove defaults; use Vault/SSM |
| Logging of sensitive data | Admin password logged at INFO | Strip |
| IP spoofing | `trust-forwarded-for=true` default | Default false; allowlist proxies |
| HTTP security headers | None set | Add HSTS, X-Content-Type-Options, Referrer-Policy |

**Missing HTTP security headers:** `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY` (or CSP). Add a `ContainerResponseFilter`.

### 4.3 Performance

- **N+1 queries:** suspected in `BookService.toResponse` (category.name, author.id) on paged listings; partially fixed for authors via `batchAuthorNames`, not for categories. Add `JOIN FETCH` in the repository paging queries.
- **Connection pool:** max=10 — small for a production workload. Load-test and size appropriately.
- **Argon2 cost:** t=2/m=64MB — login hashing at maybe 150 ms per attempt (hardware-dependent). Login-flooded endpoints will saturate worker threads; throttle and consider moving the hash computation to a separate executor.
- **Blocking SMTP** on request thread — fixed by async.
- **Unbounded listings** (`/books`, `/admin/books`, `/admin/authors`) when `page`/`size` are absent — replace non-paged variants with defaulted paged.
- **File serving** is through Quarkus JVM heap (`StreamingOutput.transferTo(out)` streams correctly), but with no `Range` support, streaming a 100 MB book pins a worker thread for the whole download.

### 4.4 Scalability

- **Horizontal scaling blockers:**
  - In-memory rate limiter (`RateLimitFilter`) — pods give divergent views.
  - Scheduled cleanup (`TokenCleanupScheduler`) — runs on every pod; needs distributed lock.
  - Local file storage (`LocalFileStorageService`) — not shared across pods; migrate to S3 or persistent RWX volume.
  - `ConfigService` cache — each pod has its own. `refreshCache()` is local-only; no cache-invalidation fan-out.
  - Session revocation is DB-backed — good, horizontally consistent.
- **Vertical scaling blockers:**
  - DB pool max=10.
  - Single-process Quarkus.
- **Database concerns:** no read replica plumbing, no `quarkus-hibernate-orm.second-level-cache` to offload read load; no connection-level `statement_timeout`.

### 4.5 Error Handling

- `GlobalExceptionHandler` coverage is surprisingly comprehensive — the best file in the codebase. Edge cases still to add:
  - `OptimisticLockException` → 409.
  - Explicit `java.net.SocketTimeoutException` → 504 (for future outbound calls).
  - Structured `violations: [...]` body instead of concatenated string.
- Services throw `ApplicationException` subclasses consistently — good.
- `try { ... } catch (PersistenceException)` in `SessionService` and `PaymentService` correctly unwrap `ConstraintViolationException` — good.
- Swallowed `sendHtml` failure in `EmailService` — anti-pattern.

---

## 5. Gradle Build Review

| Item | State | Recommendation |
|------|-------|----------------|
| Quarkus platform pin | 3.26.1 via `enforcedPlatform` | Up to date (3.32.x exists); consider upgrading to LTS line |
| Java target | 17 | Consider 21 LTS once Quarkus 3.26.x ecosystem confirmed |
| Plugins | `io.quarkus` only | Add `jacoco`, `checkstyle`/`spotless`, `io.freefair.lombok` (optional) |
| Dependencies | Core Quarkus + ORM + Panache + JWT + Mailer + Validator + OpenAPI + Health | Missing micrometer, opentelemetry, flyway, cache, redis |
| Argon2 | `de.mkammerer:argon2-jvm:2.11` | Fine; ensure glibc-based base image |
| Lombok | 1.18.34 | Fine |
| MapStruct | Declared but unused | Remove or adopt |
| Test stack | JUnit5 + RestAssured + Mockito | Add Testcontainers for PG integration tests |
| `mavenLocal()` | Enabled | Disable in CI |
| Native image | No profile / binary configuration | Add `-Pnative` configuration if GraalVM is planned |
| Version conflicts | Not inspected | Run `./gradlew dependencies --configuration compileClasspath` |
| Build reproducibility | No lock file (no Gradle dependency locking) | Enable `dependencyLocking { lockAllConfigurations() }` |
| Optional: container image | None | Add `quarkus-container-image-jib` for fast repeatable builds |

---

## 6. Production Readiness Checklist

### P0 (blockers — must-fix before any prod traffic)

- [ ] Remove hardcoded admin password and seeder-secret defaults; rotate SMTP Gmail app-password immediately (it's already in Git history — revoke on Google).
- [ ] Replace `schema-management=update` with Flyway; ship baseline migration; verify on a staging DB.
- [ ] Remove or role-gate `/admin/seed` HTTP endpoint; move to startup-time bootstrap.
- [ ] Lock CORS to explicit origin list; disable `*`.
- [ ] Default `security.trust-forwarded-for` to `false`; configure trusted-proxy allowlist where a reverse proxy exists.
- [ ] Disable the in-memory rate limiter on multi-pod deployments; back with Redis for production.
- [ ] Make SMTP send asynchronous and add retries; alert on failure.
- [ ] Persist uploaded files in S3 (or persistent volume); `/tmp/uploads` is ephemeral.
- [ ] Remove plaintext password emailing for authors; use one-time setup link.
- [ ] Strip plaintext password from seeder logs.
- [ ] Introduce a `%prod` profile; remove `quarkus.profile=local` from `application.properties`.

### P1 (high priority — fix in the first prod sprint)

- [ ] Add `quarkus-micrometer-registry-prometheus` and export `/q/metrics`.
- [ ] Add `quarkus-opentelemetry` with OTLP exporter; correlate logs with trace IDs.
- [ ] Structured JSON logs (`quarkus-logging-json`) in prod.
- [ ] Split `smallrye-health` liveness vs readiness (readiness should probe DB + mailer + storage).
- [ ] Add HTTP security headers (HSTS, X-Content-Type-Options, Referrer-Policy, CSP).
- [ ] Remove `mavenLocal()` from CI builds.
- [ ] Pagination on all listing endpoints; remove non-paged variants.
- [ ] Add indexes on `Book.file_key`, `Book.author_id`, `Book.category_id`; composite `(status, is_published, published_date DESC)` for listings.
- [ ] `JOIN FETCH` category/author in book paged repository queries.
- [ ] Add optimistic-lock (`@Version`) to `User` entity.
- [ ] Extract `CallerContext` bean to remove the six copies of `extractUserId()`/`extractClientIp()`.
- [ ] Drive admin lookup from `RoleName.ADMIN` in `BookService`, not hardcoded email.
- [ ] Upgrade Argon2 parameters to OWASP 2023 baseline (`t=3, m=65536`).
- [ ] Add HMAC pepper to refresh-token hashing (`sessionService`/`TokenHashUtil`).
- [ ] Magic-byte cross-validation on uploads; per-user upload quota.
- [ ] Rate-limit per authenticated user, not just IP.
- [ ] Email verification must actually gate registration (currently bypassed — `emailVerified=true` on register).
- [ ] Multistage Dockerfile; non-root runtime user; add `HEALTHCHECK`.
- [ ] Clustered scheduler via Postgres advisory lock.
- [ ] Cache invalidation fan-out for `ConfigService` (use Redis pub/sub or DB-notification listener).

### P2 (medium — hardening)

- [ ] `/register` no-enumeration response; equalize login timing for unknown emails.
- [ ] `OptimisticLockException` handled explicitly in `GlobalExceptionHandler`.
- [ ] Book soft-delete must also delete the underlying file asset after retention window.
- [ ] Full-text search (pg_trgm or ElasticSearch) on book title/keywords.
- [ ] `ETag` + `If-None-Match` + `Range` support in `FileResource`.
- [ ] Virus scanning on uploaded book files.
- [ ] JWKS rotation via Vault/SSM — add `kid` claim.
- [ ] Integration tests with Testcontainers + PostgreSQL.
- [ ] Gradle dependency locking; `jacoco` + `spotless`.
- [ ] OpenAPI contract tests.

### P3 (nice-to-have)

- [ ] GraalVM native image.
- [ ] Second-level Hibernate cache.
- [ ] Read replica routing via Quarkus datasource tenant.
- [ ] Admin UI for `ConfigParam`.
- [ ] Qute templates for emails.
- [ ] Drop MapStruct dep (unused).
- [ ] Rename `rootProject.name` to `ebook-be`.

---

## 7. Refactoring Roadmap

### Phase 1 — Quick Wins (days, not weeks)

1. **Security hygiene** (0.5 day): revoke the Gmail app-password, rotate seed secret, remove defaults from `application-local.properties`.
2. **CORS + forwarded-for defaults** (0.5 day): switch defaults to secure, introduce explicit overlay for dev.
3. **Seeder out of HTTP** (1 day): `@Observes StartupEvent` gated by profile; generate admin password at first-run and write to a one-time file.
4. **Flyway baseline + `schema-management=none`** (2 days): dump current dev schema via `pg_dump --schema-only`, convert to `V001__baseline.sql`, hook `quarkus-flyway`, test.
5. **Multistage Dockerfile + HEALTHCHECK + non-root** (0.5 day).
6. **Structured JSON logs + correlation ID filter** (0.5 day).
7. **Async SMTP** (1 day): switch to `ReactiveMailer` or wrap sync calls in a `ManagedExecutor`; bound queue size; add retry with backoff; expose failure counter.
8. **Config overlays** (0.5 day): `%prod` overlay; remove `quarkus.profile=local` from default.
9. **HTTP security headers** (0.25 day): `ContainerResponseFilter` with HSTS, XCTO, Referrer-Policy.
10. **Strip admin password from seeder logs** (0.1 day).

### Phase 2 — Medium (weeks)

1. **Redis-backed rate limiter** (3 days): pluggable, fail-open, per-user + per-IP buckets.
2. **Persistent file storage** (3 days): S3 `FileStorageService` implementation already scaffolded; wire it, add pre-signed URL support for `BOOK` kind so the JVM doesn't stream them.
3. **Observability bundle** (3 days): Micrometer + Prometheus + OpenTelemetry OTLP exporter; Grafana dashboards for p50/p95/p99, error rate, DB pool, rate-limit hits; health-probe split.
4. **DB hygiene** (2 days): add indexes; `JOIN FETCH` in paged queries; enable `Hibernate statistics` in non-prod; set `statement_timeout` at the DB side.
5. **Entity hardening** (2 days): `@Version` on `User`; composite constraints on `UserRole`, `UserBook`, `CartItem`; length caps everywhere.
6. **Email-verification gate** (1 day): flip `emailVerified=false` on register; wire login to reject unverified; update tests.
7. **Argon2 param bump + pepper for refresh tokens** (1 day).
8. **Pagination enforcement** (2 days): remove non-paged variants; update OpenAPI.
9. **Testcontainers integration suite** (5 days): at minimum one happy-path + one security test per resource.

### Phase 3 — Advanced

1. **Clustered scheduler** via Postgres advisory locks or Quarkus-scheduler's clustering support.
2. **JWKS + `kid` rotation** behind Vault/SSM; coordinate with every token verifier.
3. **Optimistic-lock retry**: AOP-style `@Retryable` for admin book approve/reject races.
4. **Search module**: ElasticSearch or Meilisearch for book discovery; Debezium-style CDC if needed.
5. **Native image**: for cold-start-sensitive deployments.
6. **GDPR deletion**: implement a durable `deleteUser` service that anonymises `AuditLog`, severs `UserBook` entitlements per retention policy, and purges `UserProfile` / `Device` / `Session`.
7. **Payment gateway integration** replacing the mock with an idempotency-preserving real provider.

---

## 8. Final Verdict

### Is this production ready? **No — not yet.**

**Why not:**

The codebase has the right shape and several quietly well-executed mechanisms: refresh-token rotation with reuse detection, idempotency-key checkout with ledger reconciliation and a unique-constraint race fallback, a thorough exception mapper, proper Argon2id password hashing, a fail-open rate limiter with per-path rules, and defense-in-depth path-traversal guards on the file store. Those are the signs of an engineer who has seen real incidents.

But the deployment posture undermines the code. A fresh deployment with default env vars exposes an admin takeover path (`/admin/seed` + a predictable secret + `Admin@12345` logged at INFO). `schema-management=update` with no migration tool turns the first bad entity change into a production outage. `/tmp/uploads` disappears on every pod restart. CORS is wildcard-open with credentialed auth. The rate limiter is strictly per-pod and lies about quota the moment you horizontal-scale. SMTP is synchronous with swallowed failures. `security.trust-forwarded-for` defaults to `true` so every security decision tied to IP is trust-on-first-header. None of these are subtle — they are systemic hygiene gaps that betray a codebase that has worked on a single laptop in a single pod without having yet been operated.

**What "production ready" would look like:**

All P0 items in the checklist resolved, the Phase 1 roadmap shipped, a staging environment running with realistic load for 48 hours clean, alerting on the key signals (error rate, p99 latency, rate-limit hits, SMTP failures, DB pool exhaustion), and a rehearsed rollback procedure backed by Flyway migrations. Until then, this is a strong MVP but a soft target.

**Realistic timeline to prod-ready:** **2–3 weeks** of focused work for one senior engineer on Phase 1 + the critical Phase 2 items (Redis rate-limit, S3 storage, observability, DB hardening). Phase 3 can proceed in parallel with production traffic.
