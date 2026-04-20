package com.ebook.auth.resource;

import com.ebook.common.exception.InternalServerException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/.well-known")
public class InternalSsoResource {

    private static final Logger LOG = Logger.getLogger(InternalSsoResource.class);

    @GET
    @Path("/jwks.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJwks() {
        try (InputStream stream = getClass().getResourceAsStream("/publicKey.json")) {
            if (stream == null) {
                LOG.error("publicKey.json not found on classpath — JWKS endpoint unavailable");
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity("{\"error\": \"JWKS configuration not available\"}")
                        .build();
            }
            String jwks = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Response.ok(jwks).build();
        } catch (Exception e) {
            throw new InternalServerException("Failed to read JWKS", e);
        }
    }
}
