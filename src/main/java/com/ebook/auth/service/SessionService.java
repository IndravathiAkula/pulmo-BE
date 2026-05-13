package com.ebook.auth.service;

import com.ebook.auth.entity.Device;
import com.ebook.auth.entity.Session;
import com.ebook.user.entity.User;
import com.ebook.auth.repository.SessionRepository;
import com.ebook.common.service.ConfigService;
import com.ebook.common.util.TokenHashUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ConfigService configService;

    public SessionService(SessionRepository sessionRepository, ConfigService configService) {
        this.sessionRepository = sessionRepository;
        this.configService = configService;
    }

    @Transactional
    public Session createSession(User user, Device device, String ipAddress, String userAgent, String refreshToken) {
        int sessionExpiryDays = configService.getInt("auth.session-expiry-days", 30);

        Session session = new Session();
        session.setUser(user);
        session.setRefreshTokenHash(TokenHashUtil.sha256Base64(refreshToken));
        session.setDevice(device);
        session.setIpAddress(ipAddress);
        session.setUserAgent(userAgent);
        session.setExpiresAt(Instant.now().plus(sessionExpiryDays, ChronoUnit.DAYS));
        session.setRevoked(false);
        try {
            sessionRepository.save(session);
            sessionRepository.flush();
        } catch (jakarta.persistence.PersistenceException e) {
            // Hitting the new refresh_token_hash unique constraint here is astronomically
            // unlikely (2^-256 collision with SecureRandom). If it ever fires, something
            // upstream is reusing tokens — fail loudly so the cause gets investigated
            // instead of letting the generic PersistenceException fall through to a 500.
            if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                throw new IllegalStateException(
                        "Refresh-token hash collision while creating session for user " + user.getId()
                                + " — investigate token-generation source", e);
            }
            throw e;
        }
        return session;
    }

    @Transactional
    public void revokeSession(UUID sessionId) {
        Optional<Session> opt = sessionRepository.findByIdOptional(sessionId);
        opt.ifPresent(session -> {
            session.setRevoked(true);
            sessionRepository.update(session);
        });
    }

    @Transactional
    public void revokeAllUserSessions(UUID userId) {
        sessionRepository.revokeAllUserSessions(userId);
    }

    public Optional<Session> findValidSessionByToken(String refreshToken) {
        String hash = TokenHashUtil.sha256Base64(refreshToken);
        Optional<Session> opt = sessionRepository.findByRefreshTokenHash(hash);
        if (opt.isPresent()) {
            Session session = opt.get();
            // Reject expired OR revoked sessions: a revoked session must never be usable for
            // refresh or logout, otherwise rotation + global logout are bypassable (P1 #12).
            if (session.isRevoked() || session.getExpiresAt().isBefore(Instant.now())) {
                return Optional.empty();
            }
        }
        return opt;
    }

    /**
     * Reuse detection. Returns the session (even if revoked) for the given raw refresh token.
     * The refresh flow uses this to tell "unknown token" from "already-rotated token" — the
     * latter is a theft signal and must trigger a global revocation.
     */
    public Optional<Session> findAnySessionByToken(String refreshToken) {
        String hash = TokenHashUtil.sha256Base64(refreshToken);
        return sessionRepository.findByRefreshTokenHashIncludingRevoked(hash);
    }
}
