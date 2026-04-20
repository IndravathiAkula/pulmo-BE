package com.ebook.common.resource;

import com.ebook.common.dto.ApiResponse;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Lightweight health probe the frontend BFF and ops tooling can hit.
 * Quarkus also exposes a full SmallRye Health tree at /q/health — this
 * endpoint wraps a simple UP status in the standard ApiResponse envelope.
 */
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class HealthResource {

    @GET
    public Response health() {
        return Response.ok(ApiResponse.success(
                Map.of("status", "UP"),
                "Service is healthy")).build();
    }
}
