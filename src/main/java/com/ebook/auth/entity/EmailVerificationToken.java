package com.ebook.auth.entity;

import com.ebook.common.entity.BaseEntity;
import com.ebook.user.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Stores a one-time, hashed email verification token for a user.
 * The plaintext token is NEVER persisted — only its SHA-256 hash.
 * Tokens are valid for 24 hours and are single-use.
 */
@Entity
@Getter
@Setter
@Table(
    name = "t_email_verification_tokens",
    indexes = {
        @Index(name = "idx_evt_token_hash", columnList = "token_hash")
    }
)
public class EmailVerificationToken extends BaseEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hash of the plaintext verification token. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Stamped when the token is consumed. */
    @Column(name = "used_at")
    private Instant usedAt;

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    public boolean isUsed() {
        return this.usedAt != null;
    }
}
