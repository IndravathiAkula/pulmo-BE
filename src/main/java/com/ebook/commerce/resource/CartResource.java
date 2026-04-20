package com.ebook.commerce.resource;

import com.ebook.commerce.dto.*;
import com.ebook.commerce.service.CartService;
import com.ebook.common.dto.ApiResponse;
import com.ebook.common.exception.UnauthorizedException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@Path("/cart")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"USER", "ADMIN"})
public class CartResource {

    private final CartService cartService;
    private final JsonWebToken jwt;

    public CartResource(CartService cartService, JsonWebToken jwt) {
        this.cartService = cartService;
        this.jwt = jwt;
    }

    @GET
    public Response getCart() {
        UUID userId = extractUserId();
        CartResponse cart = cartService.getCart(userId);
        return Response.ok(ApiResponse.success(cart, "Cart retrieved")).build();
    }

    @POST
    @Path("/items")
    public Response addToCart(@Valid AddToCartRequest request) {
        UUID userId = extractUserId();
        CartItemResponse item = cartService.addToCart(userId, request.getBookId());
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(item, "Book added to cart"))
                .build();
    }

    @DELETE
    @Path("/items/{bookId}")
    public Response removeFromCart(@PathParam("bookId") UUID bookId) {
        UUID userId = extractUserId();
        cartService.removeFromCart(userId, bookId);
        return Response.ok(ApiResponse.success(null, "Book removed from cart")).build();
    }

    @DELETE
    public Response clearCart() {
        UUID userId = extractUserId();
        cartService.clearCart(userId);
        return Response.ok(ApiResponse.success(null, "Cart cleared")).build();
    }

    @POST
    @Path("/checkout")
    public Response checkout(@HeaderParam("Idempotency-Key") String idempotencyKey,
                             @Context HttpHeaders headers) {
        UUID userId = extractUserId();
        CheckoutResponse result = cartService.checkout(userId, idempotencyKey, extractClientIp(headers));
        return Response.ok(ApiResponse.success(result, "Purchase completed successfully")).build();
    }

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
