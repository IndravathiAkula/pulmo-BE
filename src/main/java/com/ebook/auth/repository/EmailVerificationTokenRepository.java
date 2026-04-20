package com.ebook.auth.repository;

import com.ebook.auth.entity.EmailVerificationToken;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class EmailVerificationTokenRepository extends BaseRepository<EmailVerificationToken, UUID> {

    public Optional<EmailVerificationToken> findByTokenHash(String tokenHash) {
        return find("tokenHash = ?1 AND usedAt IS NULL", tokenHash).firstResultOptional();
    }

    @Transactional
    public long deleteExpiredOrUsedBefore(Instant before) {
        return delete("(usedAt IS NOT NULL OR expiresAt < ?1) AND createdAt < ?1", before);
    }
}
