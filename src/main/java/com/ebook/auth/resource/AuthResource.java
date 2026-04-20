package com.ebook.auth.resource;

import com.ebook.auth.dto.*;
import com.ebook.auth.service.AuthService;
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

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final AuthService authService;
    private final JsonWebToken jwt;

    public AuthResource(AuthService authService, JsonWebToken jwt) {
        this.authService = authService;
        this.jwt = jwt;
    }

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        UserResponse response = authService.registerUser(request, ipAddress);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(response, "User registered successfully"))
                .build();
    }

    @POST
    @Path("/login")
    public Response login(@Valid LoginRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        String userAgent = headers.getHeaderString("User-Agent");
        TokenResponse response = authService.login(request, ipAddress, userAgent);
        return Response.ok(ApiResponse.success(response, "Login successful")).build();
    }

    @POST
    @Path("/refresh")
    public Response refresh(@Valid RefreshRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        String userAgent = headers.getHeaderString("User-Agent");
        TokenResponse response = authService.refreshToken(request, ipAddress, userAgent);
        return Response.ok(ApiResponse.success(response, "Token refreshed successfully")).build();
    }

    @POST
    @Path("/logout")
    @RolesAllowed({"USER", "ADMIN"})
    public Response logout(@Valid RefreshRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        UUID userId = extractUserId();
        authService.logout(request.getRefreshToken(), userId, ipAddress);
        return Response.ok(ApiResponse.success(null, "Logged out successfully")).build();
    }

    @POST
    @Path("/logout-all")
    @RolesAllowed({"USER", "ADMIN"})
    public Response logoutAll(@Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        UUID userId = extractUserId();
        authService.logoutAll(userId, ipAddress);
        return Response.ok(ApiResponse.success(null, "Logged out from all devices successfully")).build();
    }

    @POST
    @Path("/forgot-password")
    public Response forgotPassword(@Valid ForgotPasswordRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        authService.forgotPassword(request, ipAddress);
        return Response.ok(ApiResponse.success(null,
                "If that email is registered, a reset link has been sent.")).build();
    }

    @POST
    @Path("/reset-password")
    public Response resetPassword(@Valid ResetPasswordRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        authService.resetPassword(request, ipAddress);
        return Response.ok(ApiResponse.success(null,
                "Password has been reset. Please log in with your new password.")).build();
    }

    @POST
    @Path("/verify-email")
    public Response verifyEmail(@Valid VerifyEmailRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        authService.verifyEmail(request, ipAddress);
        return Response.ok(ApiResponse.success(null, "Email verified successfully")).build();
    }

    @POST
    @Path("/resend-verification")
    public Response resendVerification(@Valid ResendVerificationRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        authService.resendVerificationEmail(request.getEmail(), ipAddress);
        return Response.ok(ApiResponse.success(null,
                "If that email is registered and not yet verified, a verification link has been sent.")).build();
    }

    @POST
    @Path("/change-password")
    @RolesAllowed({"USER", "ADMIN"})
    public Response changePassword(@Valid ChangePasswordRequest request, @Context HttpHeaders headers) {
        String ipAddress = extractClientIp(headers);
        UUID userId = extractUserId();
        authService.changePassword(request, userId, ipAddress);
        return Response.ok(ApiResponse.success(null,
                "Password changed successfully. Please log in again.")).build();
    }

    @GET
    @Path("/me")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getCurrentUser() {
        UUID userId = extractUserId();
        UserResponse response = authService.getCurrentUser(userId);
        return Response.ok(ApiResponse.success(response, "User info retrieved")).build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
