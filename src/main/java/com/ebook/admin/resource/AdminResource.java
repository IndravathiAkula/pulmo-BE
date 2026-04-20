package com.ebook.admin.resource;

import com.ebook.admin.dto.AuthorResponse;
import com.ebook.admin.dto.CreateAuthorRequest;
import com.ebook.admin.dto.RejectBookRequest;
import com.ebook.admin.dto.UpdateAuthorRequest;
import com.ebook.admin.service.AdminAuthorService;
import com.ebook.admin.service.AdminSeederService;
import com.ebook.catalog.dto.BookResponse;
import com.ebook.catalog.repository.BookRepository;
import com.ebook.catalog.service.BookService;
import com.ebook.catalog.dto.BookApprovalLogResponse;
import com.ebook.common.dto.ApiResponse;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.exception.UnauthorizedException;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.UUID;

@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    private final AdminSeederService seederService;
    private final AdminAuthorService authorService;
    private final BookService bookService;
    private final JsonWebToken jwt;

    @ConfigProperty(name = "app.seed-secret", defaultValue = "ebookhub-seed-secret-2026")
    String seedSecret;

    public AdminResource(AdminSeederService seederService, AdminAuthorService authorService,
                         BookService bookService, JsonWebToken jwt) {
        this.seederService = seederService;
        this.authorService = authorService;
        this.bookService = bookService;
        this.jwt = jwt;
    }

    // ─────────────────────────── SEEDER ───────────────────────────

    @POST
    @Path("/seed")
    @PermitAll
    public Response seed(@HeaderParam("X-Seed-Secret") String secret) {
        if (secret == null || !secret.equals(seedSecret)) {
            throw new UnauthorizedException("Invalid or missing seed secret");
        }
        AdminSeederService.SeederResult result = seederService.seed();
        if (result == AdminSeederService.SeederResult.ALREADY_SEEDED) {
            return Response.ok(ApiResponse.success(null, "Master data already seeded. No changes made.")).build();
        }
        return Response.ok(ApiResponse.success(null, "Master data seeded successfully.")).build();
    }

    // ─────────────────────────── AUTHOR CRUD (Admin only) ───────────────────────────

    @GET
    @Path("/authors")
    @RolesAllowed({"ADMIN"})
    public Response getAllAuthors(@QueryParam("page") Integer page,
                                  @QueryParam("size") Integer size,
                                  @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                java.util.Set.of("createdAt", "firstName", "lastName"), "createdAt");
        if (req == null) {
            List<AuthorResponse> authors = authorService.getAllAuthors();
            return Response.ok(ApiResponse.success(authors, "All authors retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                authorService.getAllAuthors(req), "All authors retrieved")).build();
    }

    @GET
    @Path("/authors/{id}")
    @RolesAllowed({"ADMIN"})
    public Response getAuthorById(@PathParam("id") UUID id) {
        AuthorResponse response = authorService.getAuthorById(id);
        return Response.ok(ApiResponse.success(response, "Author retrieved")).build();
    }

    @POST
    @Path("/authors")
    @RolesAllowed({"ADMIN"})
    public Response createAuthor(@Valid CreateAuthorRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        AuthorResponse response = authorService.createAuthor(request, ipAddress);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(response, "Author created. Verification email sent."))
                .build();
    }

    @PUT
    @Path("/authors/{id}")
    @RolesAllowed({"ADMIN"})
    public Response updateAuthor(@PathParam("id") UUID id, @Valid UpdateAuthorRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        AuthorResponse response = authorService.updateAuthor(id, request, ipAddress);
        return Response.ok(ApiResponse.success(response, "Author updated successfully")).build();
    }

    @DELETE
    @Path("/authors/{id}")
    @RolesAllowed({"ADMIN"})
    public Response deleteAuthor(@PathParam("id") UUID id, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        authorService.deactivateAuthor(id, ipAddress);
        return Response.ok(ApiResponse.success(null, "Author deactivated successfully")).build();
    }

    @PATCH
    @Path("/authors/{id}/toggle")
    @RolesAllowed({"ADMIN"})
    public Response toggleAuthor(@PathParam("id") UUID id, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        AuthorResponse response = authorService.toggleActive(id, ipAddress);
        String message = response.isActive() ? "Author activated" : "Author deactivated";
        return Response.ok(ApiResponse.success(response, message)).build();
    }

    @POST
    @Path("/authors/{id}/resend-verification")
    @RolesAllowed({"ADMIN"})
    public Response resendAuthorVerification(@PathParam("id") UUID id, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        authorService.resendVerification(id, ipAddress);
        return Response.ok(ApiResponse.success(null, "Verification email resent")).build();
    }

    // ─────────────────────────── BOOK APPROVAL (Admin only) ───────────────────────────

    @GET
    @Path("/books")
    @RolesAllowed({"ADMIN"})
    public Response getAllBooks(@QueryParam("page") Integer page,
                                @QueryParam("size") Integer size,
                                @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                BookRepository.SORTABLE_FIELDS, BookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<BookResponse> books = bookService.getAllBooksForAdmin();
            return Response.ok(ApiResponse.success(books, "All books retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                bookService.getAllBooksForAdmin(req), "All books retrieved")).build();
    }

    @GET
    @Path("/books/pending")
    @RolesAllowed({"ADMIN"})
    public Response getPendingBooks(@QueryParam("page") Integer page,
                                    @QueryParam("size") Integer size,
                                    @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                BookRepository.SORTABLE_FIELDS, BookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<BookResponse> books = bookService.getPendingBooks();
            return Response.ok(ApiResponse.success(books, "Pending books retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                bookService.getPendingBooks(req), "Pending books retrieved")).build();
    }

    @GET
    @Path("/books/{id}")
    @RolesAllowed({"ADMIN"})
    public Response getBookForAdmin(@PathParam("id") UUID id) {
        BookResponse book = bookService.getBookForAdmin(id);
        return Response.ok(ApiResponse.success(book, "Book retrieved")).build();
    }

    @PATCH
    @Path("/books/{id}/approve")
    @RolesAllowed({"ADMIN"})
    public Response approveBook(@PathParam("id") UUID id) {
        UUID adminId = extractUserId();
        BookResponse book = bookService.approveBook(adminId, id);
        return Response.ok(ApiResponse.success(book, "Book approved and published")).build();
    }

    @PATCH
    @Path("/books/{id}/reject")
    @RolesAllowed({"ADMIN"})
    public Response rejectBook(@PathParam("id") UUID id, RejectBookRequest request) {
        UUID adminId = extractUserId();
        String reason = (request != null && request.getReason() != null) ? request.getReason() : "No reason provided";
        BookResponse book = bookService.rejectBook(adminId, id, reason);
        return Response.ok(ApiResponse.success(book, "Book rejected")).build();
    }

    @GET
    @Path("/books/{id}/history")
    @RolesAllowed({"ADMIN"})
    public Response getBookApprovalHistory(@PathParam("id") UUID id) {
        List<BookApprovalLogResponse> history = bookService.getApprovalHistory(id);
        return Response.ok(ApiResponse.success(history, "Approval history retrieved")).build();
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private UUID extractUserId() {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new UnauthorizedException("Invalid token: missing subject");
        }
        return UUID.fromString(subject);
    }

    private String extractClientIp(HttpHeaders headers) {
        String xForwardedFor = headers.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return "unknown";
    }
}
