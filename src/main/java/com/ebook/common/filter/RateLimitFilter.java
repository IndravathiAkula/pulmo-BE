package com.ebook.common.filter;

import com.ebook.common.dto.ErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP rate limiter for authentication endpoints.
 * Uses a fixed-window counter strategy.
 */
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
    private static final long WINDOW_MILLIS = 60_000;

    private final ConcurrentHashMap<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("/auth/")) {
            return;
        }

        String ip = extractClientIp(ctx);
        int limit = resolveLimit(path);
        String key = ip + ":" + normalizePath(path);

        RateLimitBucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(WINDOW_MILLIS)) {
                return new RateLimitBucket();
            }
            return existing;
        });

        if (bucket.incrementAndCheck(WINDOW_MILLIS) > limit) {
            LOG.warnf("Rate limit exceeded for IP=%s on path=%s", ip, path);
            ctx.abortWith(buildRateLimitResponse());
        }

        cleanupStaleEntries();
    }

    private int resolveLimit(String path) {
        if (path.contains("/login")) return LOGIN_MAX_REQUESTS;
        if (path.contains("/register")) return REGISTER_MAX_REQUESTS;
        if (path.contains("/refresh")) return REFRESH_MAX_REQUESTS;
        if (path.contains("/forgot-password")) return FORGOT_PASSWORD_MAX_REQUESTS;
        if (path.contains("/reset-password")) return RESET_PASSWORD_MAX_REQUESTS;
        if (path.contains("/change-password")) return CHANGE_PASSWORD_MAX_REQUESTS;
        if (path.contains("/verify-email")) return VERIFY_EMAIL_MAX_REQUESTS;
        if (path.contains("/resend-verification")) return RESEND_VERIFICATION_MAX_REQUESTS;
        return DEFAULT_MAX_REQUESTS;
    }

    private String normalizePath(String path) {
        if (path.contains("/login")) return "/auth/login";
        if (path.contains("/register")) return "/auth/register";
        if (path.contains("/refresh")) return "/auth/refresh";
        if (path.contains("/forgot-password")) return "/auth/forgot-password";
        if (path.contains("/reset-password")) return "/auth/reset-password";
        if (path.contains("/change-password")) return "/auth/change-password";
        if (path.contains("/verify-email")) return "/auth/verify-email";
        if (path.contains("/resend-verification")) return "/auth/resend-verification";
        return "/auth/*";
    }

    private String extractClientIp(ContainerRequestContext ctx) {
        String forwarded = ctx.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return "unknown";
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
            if (now - windowStart.get() > windowMillis) {
                windowStart.set(now);
                count.set(1);
                return 1;
            }
            return count.incrementAndGet();
        }
    }
}
