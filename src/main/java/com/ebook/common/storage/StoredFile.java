package com.ebook.common.storage;

/**
 * Result of a successful upload write. {@code key} is the stable storage key
 * (e.g. {@code covers/abc-123.jpg}) — the frontend is responsible for
 * assembling the absolute URL from its configured backend origin and the
 * known {@code /files/} route.
 */
public record StoredFile(
        String key,
        String contentType,
        long sizeBytes,
        UploadKind kind
) {}
