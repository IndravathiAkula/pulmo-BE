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
        sessionRepository.save(session);
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
        if (opt.isPresent() && opt.get().getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return opt;
    }
}
