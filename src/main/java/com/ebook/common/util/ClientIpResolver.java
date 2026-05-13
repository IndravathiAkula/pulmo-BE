package com.ebook.common.util;

import java.util.regex.Pattern;

/**
 * Normalizes and validates client IP addresses extracted from request headers.
 *
 * <p>Addresses P1 #10 (X-Forwarded-For spoofing):
 * <ul>
 *     <li>Validates that the header value looks like an IPv4 or IPv6 address — arbitrary
 *         attacker-controlled strings are rejected and replaced with the fallback.</li>
 *     <li>Truncates to 45 characters (the ceiling for IPv4-mapped IPv6 notation, matching the
 *         {@code ip_address} column length).</li>
 *     <li>Allows deployments to turn off {@code X-Forwarded-For} trust entirely via the
 *         {@code security.trust-forwarded-for} config flag — flip it to {@code false} when the
 *         app is exposed directly (no reverse proxy) to prevent header-based rate-limit bypass.</li>
 * </ul>
 *
 * <p>This class is intentionally a plain static utility so it can be called both from JAX-RS
 * resources (where it receives {@code HttpHeaders}) and from {@code ContainerRequestFilter}
 * implementations (where it receives the raw header string). Callers pass the trust flag in
 * to avoid forcing them to become CDI consumers.
 */
public final class ClientIpResolver {

    private static final String FALLBACK = "unknown";
    private static final int MAX_IP_LEN = 45;

    // Pragmatic validators — we only need to reject obvious garbage ("<script>...", "; DROP TABLE")
    // and let plausible IPv4/IPv6 strings through. Full RFC validation is overkill for log/audit fields.
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+(%[0-9a-zA-Z._-]+)?$");

    private ClientIpResolver() {
    }

    /**
     * Resolve the client IP from an {@code X-Forwarded-For} header value.
     *
     * @param xForwardedFor   the raw header value (may be null or blank)
     * @param trustForwardedFor when {@code false}, the header is ignored entirely
     * @return a validated IP string, or {@link #FALLBACK} when no trusted/valid IP is available
     */
    public static String resolve(String xForwardedFor, boolean trustForwardedFor) {
        if (!trustForwardedFor) {
            return FALLBACK;
        }
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return FALLBACK;
        }
        String first = xForwardedFor.split(",", 2)[0].trim();
        if (first.isEmpty() || first.length() > MAX_IP_LEN) {
            return FALLBACK;
        }
        if (IPV4.matcher(first).matches() || IPV6.matcher(first).matches()) {
            return first;
        }
        return FALLBACK;
    }
}
