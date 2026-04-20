package com.ebook.common.storage;

import com.ebook.common.dto.ApiResponse;
import com.ebook.common.exception.InternalServerException;
import com.ebook.common.exception.ValidationException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Multipart upload endpoint — {@code POST /uploads} returning
 * {@link UploadResponse}. Authenticated for all kinds; no admin check (authors
 * and readers both upload — covers, previews, profile images, book PDFs).
 *
 * <p>Defence in depth: the resource re-validates MIME/size before calling
 * {@link FileStorageService}; the service also validates. Browser-side
 * validation is a UX nicety only.
 */
@Path("/uploads")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
@RolesAllowed({"USER", "ADMIN"})
public class UploadResource {

    private final FileStorageService storageService;

    public UploadResource(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @POST
    public Response upload(@RestForm("file") FileUpload file,
                           @RestForm("kind") String kindRaw) {
        if (file == null) {
            throw new ValidationException("'file' part is required");
        }

        UploadKind kind = UploadKind.fromString(kindRaw);

        String contentType = file.contentType();
        if (contentType != null) {
            int semi = contentType.indexOf(';');
            if (semi > 0) contentType = contentType.substring(0, semi).trim();
        }

        long size = file.size();
        if (size <= 0) {
            throw new ValidationException("Uploaded file is empty");
        }

        StoredFile stored;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            stored = storageService.store(kind, in, file.fileName(), contentType, size);
        } catch (IOException e) {
            throw new InternalServerException("Failed to read uploaded file", e);
        }

        UploadResponse body = UploadResponse.builder()
                .url(stored.url())
                .key(stored.key())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .kind(stored.kind().name().toLowerCase())
                .build();

        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(body, "Upload successful"))
                .build();
    }
}
