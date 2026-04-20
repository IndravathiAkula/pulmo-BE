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

    @Scheduled(every = "1h")
    @Transactional
    void cleanupExpiredData() {
        Instant sessionThreshold = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant tokenThreshold = Instant.now().minus(7, ChronoUnit.DAYS);

        long sessions = sessionRepository.deleteExpiredOrRevokedBefore(sessionThreshold);
        long resetTokens = passwordResetTokenRepository.deleteExpiredOrUsedBefore(tokenThreshold);
        long verificationTokens = emailVerificationTokenRepository.deleteExpiredOrUsedBefore(tokenThreshold);

        if (sessions > 0 || resetTokens > 0 || verificationTokens > 0) {
            LOG.infof("Cleanup complete — sessions: %d, reset tokens: %d, verification tokens: %d",
                    sessions, resetTokens, verificationTokens);
        }
    }
}
