package com.ebook.common.storage;

import com.ebook.common.exception.InternalServerException;
import com.ebook.common.exception.ValidationException;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Local-disk implementation backed by {@code storage.local.base-dir}.
 *
 * <p>Activated when {@code storage.provider=local} (default).
 */
@ApplicationScoped
@LookupIfProperty(name = "storage.provider", stringValue = "local", lookupIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private static final Logger LOG = Logger.getLogger(LocalFileStorageService.class);

    @ConfigProperty(name = "storage.local.base-dir")
    String baseDir;

    @ConfigProperty(name = "storage.local.public-base-url", defaultValue = "/ebook/files")
    String publicBaseUrl;

    private Path baseDirPath;

    @PostConstruct
    void init() {
        this.baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDirPath);
            for (UploadKind k : UploadKind.values()) {
                Files.createDirectories(baseDirPath.resolve(k.prefix()));
            }
        } catch (IOException e) {
            throw new InternalServerException("Failed to initialize storage directory: " + baseDirPath, e);
        }
        LOG.infof("Local file storage initialized: %s (public=%s)", baseDirPath, publicBaseUrl);
    }

    @Override
    public StoredFile store(UploadKind kind, InputStream data, String originalFilename,
                             String contentType, long sizeBytes) {
        if (data == null) {
            throw new ValidationException("File stream is required");
        }
        if (sizeBytes > kind.maxBytes()) {
            throw new ValidationException("File exceeds " + humanReadable(kind.maxBytes())
                    + " for kind '" + kind.name().toLowerCase() + "'");
        }
        if (contentType == null || !kind.allowedMimeTypes().contains(contentType.toLowerCase())) {
            throw new ValidationException("MIME type '" + contentType
                    + "' not allowed for kind '" + kind.name().toLowerCase()
                    + "'. Allowed: " + kind.allowedMimeTypes());
        }

        String extension = extractExtension(originalFilename, contentType);
        String key = kind.prefix() + "/" + UUID.randomUUID() + extension;
        Path target = baseDirPath.resolve(key).normalize();

        // Defense in depth: reject any key that escapes the base directory.
        if (!target.startsWith(baseDirPath)) {
            throw new ValidationException("Invalid storage path");
        }

        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InternalServerException("Failed to write file: " + key, e);
        }

        long actualSize;
        try {
            actualSize = Files.size(target);
        } catch (IOException e) {
            actualSize = sizeBytes;
        }

        String url = publicBaseUrl + "/" + key;
        LOG.infof("Stored file: key=%s size=%d mime=%s", key, actualSize, contentType);
        return new StoredFile(key, url, contentType, actualSize, kind);
    }

    @Override
    public Optional<LoadedFile> load(String key) {
        if (key == null || key.isBlank()) return Optional.empty();

        Path target = baseDirPath.resolve(key).normalize();
        if (!target.startsWith(baseDirPath)) return Optional.empty();
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) return Optional.empty();

        try {
            String contentType = Files.probeContentType(target);
            long size = Files.size(target);
            InputStream stream = Files.newInputStream(target);
            return Optional.of(new LoadedFile(stream,
                    contentType != null ? contentType : "application/octet-stream", size));
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read file: %s", key);
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String key) {
        if (key == null || key.isBlank()) return false;
        Path target = baseDirPath.resolve(key).normalize();
        if (!target.startsWith(baseDirPath)) return false;
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to delete file: %s", key);
            return false;
        }
    }

    @Override
    public Optional<Path> resolveLocalPath(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        Path target = baseDirPath.resolve(key).normalize();
        if (!target.startsWith(baseDirPath)) return Optional.empty();
        return Files.isRegularFile(target) ? Optional.of(target) : Optional.empty();
    }

    private static String extractExtension(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
            }
        }
        // Fall back to content-type guess
        return switch (contentType == null ? "" : contentType.toLowerCase()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "application/pdf" -> ".pdf";
            case "application/epub+zip" -> ".epub";
            default -> "";
        };
    }

    private static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}
