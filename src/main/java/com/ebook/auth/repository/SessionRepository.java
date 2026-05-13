package com.ebook.auth.repository;

import com.ebook.auth.entity.Session;
import com.ebook.common.repository.BaseRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SessionRepository extends BaseRepository<Session, UUID> {

    public Optional<Session> findByRefreshTokenHash(String refreshTokenHash) {
        return find("refreshTokenHash = ?1 and revoked = false", refreshTokenHash).firstResultOptional();
    }

    /**
     * Lookup that ignores the revoked flag. Used by refresh-token reuse detection:
     * if the inbound token maps to a revoked session, the token has been rotated
     * already and someone is replaying it — treat as theft and revoke all sessions.
     */
    public Optional<Session> findByRefreshTokenHashIncludingRevoked(String refreshTokenHash) {
        return find("refreshTokenHash", refreshTokenHash).firstResultOptional();
    }

    public void revokeAllUserSessions(UUID userId) {
        update("revoked = true where user.id = ?1 and revoked = false", userId);
    }

    @Transactional
    public long deleteExpiredOrRevokedBefore(Instant before) {
        return delete("(revoked = true OR expiresAt < ?1) AND createdAt < ?1", before);
    }
}
