package com.ebook.common.scheduler;

import com.ebook.auth.repository.EmailVerificationTokenRepository;
import com.ebook.auth.repository.PasswordResetTokenRepository;
import com.ebook.auth.repository.SessionRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodically cleans up expired/consumed tokens and revoked sessions
 * to prevent unbounded table growth.
 */
@ApplicationScoped
public class TokenCleanupScheduler {

    private static final Logger LOG = Logger.getLogger(TokenCleanupScheduler.class);

    private final SessionRepository sessionRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;

    public TokenCleanupScheduler(
            SessionRepository sessionRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository) {
        this.sessionRepository = sessionRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
    }

    // No outer @Transactional — each repository method already declares its own @Transactional
    // so the three deletes commit (or fail) independently. Wrapping the whole scheduler body
    // in one transaction means a single bad delete rolls all three back every hour, which
    // never recovers; per-call try/catch keeps the other cleanups moving even if one is stuck.
    @Scheduled(every = "1h")
    void cleanupExpiredData() {
        Instant sessionThreshold = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant tokenThreshold = Instant.now().minus(7, ChronoUnit.DAYS);

        long sessions = safeDelete("sessions",
                () -> sessionRepository.deleteExpiredOrRevokedBefore(sessionThreshold));
        long resetTokens = safeDelete("password-reset tokens",
                () -> passwordResetTokenRepository.deleteExpiredOrUsedBefore(tokenThreshold));
        long verificationTokens = safeDelete("email-verification tokens",
                () -> emailVerificationTokenRepository.deleteExpiredOrUsedBefore(tokenThreshold));

        if (sessions > 0 || resetTokens > 0 || verificationTokens > 0) {
            LOG.infof("Cleanup complete — sessions: %d, reset tokens: %d, verification tokens: %d",
                    sessions, resetTokens, verificationTokens);
        }
    }

    private long safeDelete(String label, java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled cleanup failed for %s — will retry at next tick", label);
            return 0L;
        }
    }
}
