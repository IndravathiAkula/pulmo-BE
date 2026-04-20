package com.ebook.commerce.resource;

import com.ebook.commerce.dto.CheckoutRequest;
import com.ebook.commerce.dto.PaymentResponse;
import com.ebook.commerce.dto.PurchasedBookResponse;
import com.ebook.commerce.repository.PaymentHistoryRepository;
import com.ebook.commerce.service.PaymentService;
import com.ebook.common.dto.ApiResponse;
import com.ebook.common.dto.PagedResponse;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.exception.UnauthorizedException;
import com.ebook.user.repository.UserBookRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

/**
 * User-facing payment endpoints.
 *
 * <p>Admin endpoints live in {@code AdminResource} at {@code /admin/payments/*}.
 */
@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentResource {

    private final PaymentService paymentService;
    private final JsonWebToken jwt;

    public PaymentResource(PaymentService paymentService, JsonWebToken jwt) {
        this.paymentService = paymentService;
        this.jwt = jwt;
    }

    /**
     * Initiate a mock checkout. The caller's identity is taken from the JWT sub; any {@code userId}
     * in the request body is ignored to prevent IDOR.
     *
     * <p>Strongly-recommended header: {@code Idempotency-Key: <uuid>} — repeated submissions with
     * the same key return the same payment rather than creating duplicates.
     */
    @POST
    @Path("/checkout")
    @RolesAllowed({"USER", "ADMIN"})
    public Response checkout(@Valid CheckoutRequest request,
                             @HeaderParam("Idempotency-Key") String idempotencyKey,
                             @Context HttpHeaders headers) {
        UUID userId = extractUserId();
        PaymentResponse payment = paymentService.checkout(userId, request, idempotencyKey,
                extractClientIp(headers));
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(payment, "Payment completed"))
                .build();
    }

    /** Caller's transaction history. */
    @GET
    @Path("/my")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getMyPayments(@QueryParam("page") Integer page,
                                  @QueryParam("size") Integer size,
                                  @QueryParam("sort") String sort) {
        UUID userId = extractUserId();
        PageRequest req = PageRequest.parse(page, size, sort,
                PaymentHistoryRepository.SORTABLE_FIELDS, PaymentHistoryRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            req = PageRequest.parse(0, 20, null,
                    PaymentHistoryRepository.SORTABLE_FIELDS, PaymentHistoryRepository.DEFAULT_SORT_FIELD);
        }
        PagedResponse<PaymentResponse> payments = paymentService.getUserPayments(userId, req);
        return Response.ok(ApiResponse.success(payments, "Payments retrieved")).build();
    }

    /** Single payment detail — only returned if the caller owns it. */
    @GET
    @Path("/my/{id}")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getMyPayment(@PathParam("id") UUID id) {
        UUID userId = extractUserId();
        PaymentResponse payment = paymentService.getUserPayment(userId, id);
        return Response.ok(ApiResponse.success(payment, "Payment retrieved")).build();
    }

    // ═══════════════════════════ USER LIBRARY ═══════════════════════════
    // Lives on this resource so it's discoverable next to /payments/my. Could live at /user/books
    // instead — preference is for co-location of the purchase concern.

    @GET
    @Path("/my/books")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getPurchasedBooks(@QueryParam("page") Integer page,
                                      @QueryParam("size") Integer size,
                                      @QueryParam("sort") String sort) {
        UUID userId = extractUserId();
        PageRequest req = PageRequest.parse(page, size, sort,
                UserBookRepository.SORTABLE_FIELDS, UserBookRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            req = PageRequest.parse(0, 20, null,
                    UserBookRepository.SORTABLE_FIELDS, UserBookRepository.DEFAULT_SORT_FIELD);
        }
        PagedResponse<PurchasedBookResponse> books = paymentService.getPurchasedBooks(userId, req);
        return Response.ok(ApiResponse.success(books, "Purchased books retrieved")).build();
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
