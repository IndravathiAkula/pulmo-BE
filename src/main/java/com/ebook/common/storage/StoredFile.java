package com.ebook.common.storage;

/**
 * Result of a successful upload write. {@code key} is the stable storage key
 * (e.g. {@code covers/abc-123.jpg}); {@code url} is the public-facing path
 * the frontend can use directly in {@code <img src>} or {@code <a href>}.
 */
public record StoredFile(
        String key,
        String url,
        String contentType,
        long sizeBytes,
        UploadKind kind
) {}
