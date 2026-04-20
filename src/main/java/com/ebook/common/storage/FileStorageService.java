package com.ebook.common.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Pluggable storage backend. Swap {@link LocalFileStorageService} for an
 * S3 / GCS / Azure impl by flipping {@code storage.provider} and adding a
 * CDI producer. Interface is kept narrow on purpose: store, load, delete.
 */
public interface FileStorageService {

    /** Persist the stream. Caller must have already validated size/MIME. */
    StoredFile store(UploadKind kind, InputStream data, String originalFilename,
                     String contentType, long sizeBytes);

    /**
     * Open the file at {@code key} for streaming back to the client.
     * Returns empty if the file isn't found or isn't readable.
     */
    Optional<LoadedFile> load(String key);

    /** Delete the file at {@code key}. No-op if it doesn't exist. */
    boolean delete(String key);

    /**
     * Resolve a storage key to an absolute local path when the backend is
     * local-disk. Remote backends should return {@link Optional#empty()} —
     * callers use this only for optimizations like sendfile.
     */
    Optional<Path> resolveLocalPath(String key);

    record LoadedFile(InputStream stream, String contentType, long sizeBytes) {}
}
