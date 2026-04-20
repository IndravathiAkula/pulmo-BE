package com.ebook.catalog.resource;

import com.ebook.catalog.dto.BookApprovalLogResponse;
import com.ebook.catalog.dto.BookResponse;
import com.ebook.catalog.dto.CreateBookRequest;
import com.ebook.catalog.dto.UpdateBookRequest;
import com.ebook.catalog.repository.BookRepository;
import com.ebook.catalog.service.BookService;
import com.ebook.common.dto.ApiResponse;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.exception.UnauthorizedException;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.UUID;

@Path("/books")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BookResource {

    private final BookService bookService;
    private final JsonWebToken jwt;

    public BookResource(BookService bookService, JsonWebToken jwt) {
        this.bookService = bookService;
        this.jwt = jwt;
    }

    // ═══════════════════════════ PUBLIC (published books) ═══════════════════════════

    @GET
    @PermitAll
    public Response getPublishedBooks(@QueryParam("page") Integer page,
                                      @QueryParam("size") Integer size,
                                      @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                BookRepository.SORTABLE_FIELDS, BookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<BookResponse> books = bookService.getPublishedBooks();
            return Response.ok(ApiResponse.success(books, "Books retrieved")).build();
        }
        return Response.ok(ApiResponse.success(bookService.getPublishedBooks(req), "Books retrieved")).build();
    }

    @GET
    @Path("/{id}")
    @PermitAll
    public Response getPublishedBookById(@PathParam("id") UUID id) {
        BookResponse book = bookService.getPublishedBookById(id);
        return Response.ok(ApiResponse.success(book, "Book retrieved")).build();
    }

    @GET
    @Path("/category/{categoryId}")
    @PermitAll
    public Response getPublishedBooksByCategory(@PathParam("categoryId") UUID categoryId,
                                                @QueryParam("page") Integer page,
                                                @QueryParam("size") Integer size,
                                                @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                BookRepository.SORTABLE_FIELDS, BookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<BookResponse> books = bookService.getPublishedBooksByCategory(categoryId);
            return Response.ok(ApiResponse.success(books, "Books retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                bookService.getPublishedBooksByCategory(categoryId, req), "Books retrieved")).build();
    }

    @GET
    @Path("/author/{authorId}")
    @PermitAll
    public Response getPublishedBooksByAuthor(@PathParam("authorId") UUID authorId,
                                              @QueryParam("page") Integer page,
                                              @QueryParam("size") Integer size,
                                              @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                BookRepository.SORTABLE_FIELDS, BookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<BookResponse> books = bookService.getPublishedBooksByAuthor(authorId);
            return Response.ok(ApiResponse.success(books, "Books retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                bookService.getPublishedBooksByAuthor(authorId, req), "Books retrieved")).build();
    }

    // ═══════════════════════════ AUTHOR (own books management) ═══════════════════════════

    @GET
    @Path("/my")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getMyBooks(@QueryParam("page") Integer page,
                               @QueryParam("size") Integer size,
                               @QueryParam("sort") String sort) {
        UUID authorId = extractUserId();
        PageRequest req = PageRequest.parse(page, size, sort,
                BookRepository.SORTABLE_FIELDS, BookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<BookResponse> books = bookService.getMyBooks(authorId);
            return Response.ok(ApiResponse.success(books, "Your books retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                bookService.getMyBooks(authorId, req), "Your books retrieved")).build();
    }

    @GET
    @Path("/my/{id}")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getMyBookById(@PathParam("id") UUID id) {
        UUID authorId = extractUserId();
        BookResponse book = bookService.getMyBookById(authorId, id);
        return Response.ok(ApiResponse.success(book, "Book retrieved")).build();
    }

    @POST
    @RolesAllowed({"USER", "ADMIN"})
    public Response createBook(@Valid CreateBookRequest request) {
        UUID authorId = extractUserId();
        BookResponse book = bookService.createBook(authorId, request);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(book, "Book created. Pending admin approval."))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"USER", "ADMIN"})
    public Response updateBook(@PathParam("id") UUID id, @Valid UpdateBookRequest request) {
        UUID authorId = extractUserId();
        BookResponse book = bookService.updateBook(authorId, id, request);
        return Response.ok(ApiResponse.success(book, "Book updated. Pending admin re-approval.")).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({"USER", "ADMIN"})
    public Response deleteBook(@PathParam("id") UUID id) {
        UUID authorId = extractUserId();
        bookService.deleteBook(authorId, id);
        return Response.ok(ApiResponse.success(null, "Book deleted")).build();
    }

    @GET
    @Path("/my/{id}/history")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getMyBookApprovalHistory(@PathParam("id") UUID id) {
        UUID authorId = extractUserId();
        bookService.getMyBookById(authorId, id); // validates ownership
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
}
