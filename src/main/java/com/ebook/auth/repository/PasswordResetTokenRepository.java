package com.ebook.auth.repository;

import com.ebook.auth.entity.PasswordResetToken;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PasswordResetTokenRepository extends BaseRepository<PasswordResetToken, UUID> {

    /**
     * Finds a reset token by its stored hash.
     * Only returns tokens that are not yet consumed (used_at IS NULL).
     */
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return find("tokenHash = ?1 AND usedAt IS NULL", tokenHash).firstResultOptional();
    }

    @Transactional
    public long deleteExpiredOrUsedBefore(Instant before) {
        return delete("(usedAt IS NOT NULL OR expiresAt < ?1) AND createdAt < ?1", before);
    }
}
