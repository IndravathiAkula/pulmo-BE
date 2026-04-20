package com.ebook.common.storage;

import com.ebook.catalog.entity.Book;
import com.ebook.catalog.repository.BookRepository;
import com.ebook.common.exception.ForbiddenException;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.user.repository.UserBookRepository;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Streams stored files back to the browser. Three serving tiers:
 *
 * <ul>
 *   <li>{@code covers/}, {@code previews/}, {@code profiles/} — public. No auth.</li>
 *   <li>{@code books/} — owner-only. Must have a {@code UserBook} row for the
 *       book whose {@code fileKey} matches, OR be an admin.</li>
 * </ul>
 *
 * <p>Profile images and covers render directly in {@code <img src>} so they
 * must be public; book payloads are gated so a leaked URL isn't enough to
 * steal the content.
 */
@Path("/files")
@PermitAll
public class FileResource {

    private final FileStorageService storageService;
    private final BookRepository bookRepository;
    private final UserBookRepository userBookRepository;
    private final JsonWebToken jwt;

    public FileResource(FileStorageService storageService,
                        BookRepository bookRepository,
                        UserBookRepository userBookRepository,
                        JsonWebToken jwt) {
        this.storageService = storageService;
        this.bookRepository = bookRepository;
        this.userBookRepository = userBookRepository;
        this.jwt = jwt;
    }

    @GET
    @Path("{prefix}/{filename}")
    public Response serve(@PathParam("prefix") String prefix,
                          @PathParam("filename") String filename,
                          @Context SecurityContext securityContext) {
        UploadKind kind = UploadKind.fromPrefix(prefix);
        if (kind == null) {
            throw new ResourceNotFoundException("File not found");
        }

        String key = prefix + "/" + filename;

        if (!kind.publiclyServed()) {
            enforceBookAccess(key, securityContext);
        }

        Optional<FileStorageService.LoadedFile> loaded = storageService.load(key);
        if (loaded.isEmpty()) {
            throw new ResourceNotFoundException("File not found");
        }

        FileStorageService.LoadedFile file = loaded.get();
        InputStream stream = file.stream();

        StreamingOutput body = out -> {
            try (stream) {
                stream.transferTo(out);
            }
        };

        Response.ResponseBuilder rb = Response.ok(body)
                .header("Content-Type", file.contentType())
                .header("Content-Length", String.valueOf(file.sizeBytes()));

        if (kind.publiclyServed()) {
            rb.header("Cache-Control", "public, max-age=86400");
        } else {
            rb.header("Cache-Control", "private, no-store");
        }
        return rb.build();
    }

    private void enforceBookAccess(String key, SecurityContext securityContext) {
        if (securityContext == null || !securityContext.isUserInRole("USER")
                && !securityContext.isUserInRole("ADMIN")) {
            throw new ForbiddenException("Authentication required to download this file");
        }

        if (securityContext.isUserInRole("ADMIN")) {
            return; // admins can always fetch
        }

        String subject = jwt != null ? jwt.getSubject() : null;
        if (subject == null || subject.isBlank()) {
            throw new ForbiddenException("Authentication required to download this file");
        }

        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("Invalid token subject");
        }

        Book book = bookRepository.find("fileKey", key).firstResult();
        if (book == null) {
            throw new ResourceNotFoundException("File not found");
        }
        if (book.getAuthor() != null && book.getAuthor().getId().equals(userId)) {
            return; // author can always fetch their own book
        }
        if (!userBookRepository.existsByUserAndBook(userId, book.getId())) {
            throw new ForbiddenException("You do not have access to this book");
        }
    }
}
