package com.ebook.common.filter;

import com.ebook.common.dto.ErrorResponse;
import com.ebook.common.util.ClientIpResolver;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);

    private static final int DEFAULT_MAX_REQUESTS = 20;
    private static final int LOGIN_MAX_REQUESTS = 5;
    private static final int REGISTER_MAX_REQUESTS = 5;
    private static final int REFRESH_MAX_REQUESTS = 30;
    private static final int FORGOT_PASSWORD_MAX_REQUESTS = 3;
    private static final int RESET_PASSWORD_MAX_REQUESTS = 5;
    private static final int CHANGE_PASSWORD_MAX_REQUESTS = 5;
    private static final int VERIFY_EMAIL_MAX_REQUESTS = 10;
    private static final int RESEND_VERIFICATION_MAX_REQUESTS = 3;

    // protect expensive write paths that previously had no per-IP ceiling.
    private static final int CHECKOUT_MAX_REQUESTS = 10;
    private static final int BOOKS_WRITE_MAX_REQUESTS = 30;
    private static final int UPLOADS_MAX_REQUESTS = 20;

    private static final long WINDOW_MILLIS = 60_000;

    private final ConcurrentHashMap<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

    @ConfigProperty(name = "security.trust-forwarded-for", defaultValue = "true")
    boolean trustForwardedFor;

    @Override
    public void filter(ContainerRequestContext ctx) {
        // Fail-open: if anything in the rate-limit bookkeeping throws, we do NOT want to
        // take down the endpoint. A broken limiter blocking all traffic is strictly worse
        // than a silently-disabled one — log it and let the request through.
        try {
            String path = ctx.getUriInfo().getPath();
            String method = ctx.getMethod();
            RateLimitRule rule = resolveRule(method, path);
            if (rule == null) {
                return;
            }

            String ip = ClientIpResolver.resolve(ctx.getHeaderString("X-Forwarded-For"), trustForwardedFor);
            String key = ip + ":" + rule.bucketKey;

            RateLimitBucket bucket = buckets.compute(key, (k, existing) -> {
                if (existing == null || existing.isExpired(WINDOW_MILLIS)) {
                    return new RateLimitBucket();
                }
                return existing;
            });

            if (bucket.incrementAndCheck(WINDOW_MILLIS) > rule.limit) {
                LOG.warnf("Rate limit exceeded for IP=%s on path=%s", ip, path);
                ctx.abortWith(buildRateLimitResponse());
            }

            cleanupStaleEntries();
        } catch (Exception e) {
            LOG.errorf(e, "Rate-limit filter internal error — allowing request through (path=%s)",
                    ctx.getUriInfo() != null ? ctx.getUriInfo().getPath() : "unknown");
        }
    }

    /** Returns the limit/bucket to apply to this request, or {@code null} to skip rate limiting. */
    private RateLimitRule resolveRule(String method, String path) {
        if (path.startsWith("/auth/")) {
            if (path.contains("/login")) return new RateLimitRule("/auth/login", LOGIN_MAX_REQUESTS);
            if (path.contains("/register")) return new RateLimitRule("/auth/register", REGISTER_MAX_REQUESTS);
            if (path.contains("/refresh")) return new RateLimitRule("/auth/refresh", REFRESH_MAX_REQUESTS);
            if (path.contains("/forgot-password")) return new RateLimitRule("/auth/forgot-password", FORGOT_PASSWORD_MAX_REQUESTS);
            if (path.contains("/reset-password")) return new RateLimitRule("/auth/reset-password", RESET_PASSWORD_MAX_REQUESTS);
            if (path.contains("/change-password")) return new RateLimitRule("/auth/change-password", CHANGE_PASSWORD_MAX_REQUESTS);
            if (path.contains("/verify-email")) return new RateLimitRule("/auth/verify-email", VERIFY_EMAIL_MAX_REQUESTS);
            if (path.contains("/resend-verification")) return new RateLimitRule("/auth/resend-verification", RESEND_VERIFICATION_MAX_REQUESTS);
            return new RateLimitRule("/auth/*", DEFAULT_MAX_REQUESTS);
        }
        // — write paths outside /auth. Matched on method + path prefix; GETs intentionally excluded.
        if ("POST".equalsIgnoreCase(method)) {
            if (path.contains("/payments/checkout") || path.contains("/cart/checkout")) {
                return new RateLimitRule("/checkout", CHECKOUT_MAX_REQUESTS);
            }
            if (path.startsWith("/books") || path.contains("/books/")) {
                return new RateLimitRule("/books-write", BOOKS_WRITE_MAX_REQUESTS);
            }
            if (path.startsWith("/uploads") || path.contains("/uploads/")) {
                return new RateLimitRule("/uploads", UPLOADS_MAX_REQUESTS);
            }
        }
        return null;
    }

    private record RateLimitRule(String bucketKey, int limit) {
    }

    private Response buildRateLimitResponse() {
        ErrorResponse body = ErrorResponse.builder()
                .status(429)
                .error("TOO_MANY_REQUESTS")
                .message("Rate limit exceeded. Please try again later.")
                .build();
        return Response.status(429)
                .entity(body)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Retry-After", "60")
                .build();
    }

    private void cleanupStaleEntries() {
        if (buckets.size() > 10_000) {
            buckets.entrySet().removeIf(e -> e.getValue().isExpired(WINDOW_MILLIS * 2));
        }
    }

    private static class RateLimitBucket {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger(0);

        boolean isExpired(long windowMillis) {
            return System.currentTimeMillis() - windowStart.get() > windowMillis;
        }

        int incrementAndCheck(long windowMillis) {
            long now = System.currentTimeMillis();
            long currentStart = windowStart.get();
            //  use CAS instead of a naked set(): without it two concurrent callers could
            // both observe a stale windowStart, both reset the counter, and both pass despite
            // being at the limit (effectively doubling the window's real capacity).
            if (now - currentStart > windowMillis && windowStart.compareAndSet(currentStart, now)) {
                count.set(1);
                return 1;
            }
            return count.incrementAndGet();
        }
    }
}
