package com.ebook.user.resource;

import com.ebook.common.dto.ApiResponse;
import com.ebook.common.exception.UnauthorizedException;
import com.ebook.user.dto.UpdateProfileRequest;
import com.ebook.user.dto.UserProfileResponse;
import com.ebook.user.service.UserProfileService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@Path("/user/profile")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"USER", "ADMIN"})
public class UserProfileResource {

    private final UserProfileService userProfileService;
    private final JsonWebToken jwt;

    public UserProfileResource(UserProfileService userProfileService, JsonWebToken jwt) {
        this.userProfileService = userProfileService;
        this.jwt = jwt;
    }

    @GET
    public Response getProfile() {
        UUID userId = extractUserId();
        UserProfileResponse profile = userProfileService.getProfile(userId);
        return Response.ok(ApiResponse.success(profile, "Profile retrieved successfully")).build();
    }

    @PUT
    public Response updateProfile(@Valid UpdateProfileRequest request) {
        UUID userId = extractUserId();
        UserProfileResponse profile = userProfileService.updateProfile(userId, request);
        return Response.ok(ApiResponse.success(profile, "Profile updated successfully")).build();
    }

    private UUID extractUserId() {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new UnauthorizedException("Invalid token: missing subject");
        }
        return UUID.fromString(subject);
    }
}
