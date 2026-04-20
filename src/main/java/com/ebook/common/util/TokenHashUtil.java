package com.ebook.common.util;

import com.ebook.common.exception.InternalServerException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Centralized SHA-256 hashing for tokens.
 * Two encodings exist for different use cases:
 * - Hex: for password reset and email verification tokens (stored as 64-char hex string)
 * - Base64: for refresh tokens (compact representation)
 */
public final class TokenHashUtil {

    private TokenHashUtil() {
    }

    /** SHA-256 → hex string (64 chars). Used for reset/verification tokens. */
    public static String sha256Hex(String input) {
        byte[] hash = sha256(input);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** SHA-256 → Base64 string. Used for refresh tokens. */
    public static String sha256Base64(String input) {
        byte[] hash = sha256(input);
        return Base64.getEncoder().encodeToString(hash);
    }

    /** Generates a cryptographically secure random token (URL-safe Base64, 32 bytes). */
    public static String generateSecureToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerException("SHA-256 algorithm not available", e);
        }
    }
}
