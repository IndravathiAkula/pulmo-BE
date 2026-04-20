package com.ebook.common.storage;

import com.ebook.common.exception.ValidationException;

import java.util.Set;

/**
 * Classifies what an uploaded blob represents. Drives:
 * <ul>
 *   <li>MIME allow-list (rejection at the resource layer before the write)</li>
 *   <li>Max size (bytes)</li>
 *   <li>Storage subdirectory / URL prefix (e.g. {@code covers/abc.jpg})</li>
 *   <li>Whether {@code GET /files/<prefix>/*} is publicly servable or auth-gated</li>
 * </ul>
 */
public enum UploadKind {

    COVER("covers", 5L * 1024 * 1024, true,
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif")),

    PREVIEW("previews", 10L * 1024 * 1024, true,
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf")),

    BOOK("books", 100L * 1024 * 1024, false,
            Set.of("application/pdf", "application/epub+zip")),

    PROFILE("profiles", 5L * 1024 * 1024, true,
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif"));

    private final String prefix;
    private final long maxBytes;
    private final boolean publiclyServed;
    private final Set<String> allowedMimeTypes;

    UploadKind(String prefix, long maxBytes, boolean publiclyServed, Set<String> allowedMimeTypes) {
        this.prefix = prefix;
        this.maxBytes = maxBytes;
        this.publiclyServed = publiclyServed;
        this.allowedMimeTypes = allowedMimeTypes;
    }

    public String prefix() { return prefix; }
    public long maxBytes() { return maxBytes; }
    public boolean publiclyServed() { return publiclyServed; }
    public Set<String> allowedMimeTypes() { return allowedMimeTypes; }

    public static UploadKind fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("kind is required (cover|preview|book|profile)");
        }
        try {
            return UploadKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid kind '" + value + "'. Expected: cover|preview|book|profile");
        }
    }

    public static UploadKind fromPrefix(String prefix) {
        for (UploadKind k : values()) {
            if (k.prefix.equalsIgnoreCase(prefix)) return k;
        }
        return null;
    }
}
