package com.ebook.common.exception;

import com.ebook.common.dto.ErrorResponse;
import io.quarkus.security.AuthenticationFailedException;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class GlobalExceptionHandler implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionHandler.class);

    @Override
    public Response toResponse(Exception exception) {

        // ── Application exceptions (all extend ApplicationException) ──
        if (exception instanceof ApplicationException appEx) {
            Response.Status status = appEx.getHttpStatus();
            if (status.getFamily() == Response.Status.Family.CLIENT_ERROR) {
                LOG.warnf("Client error [%s]: %s", appEx.getErrorCode(), appEx.getMessage());
            } else {
                LOG.errorf(appEx, "Server error [%s]: %s", appEx.getErrorCode(), appEx.getMessage());
            }
            return buildResponse(status, appEx.getErrorCode(), appEx.getMessage());
        }

        // ── Jakarta Bean Validation ──
        if (exception instanceof ConstraintViolationException cve) {
            String message = cve.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "; " + b);
            LOG.warnf("Validation failed: %s", message);
            return buildResponse(Response.Status.BAD_REQUEST, "VALIDATION_FAILED", message);
        }

        // ── DB-level constraint violations (unique / @Check / NOT NULL) → 409 ──
        // The wrapped exception is org.hibernate.exception.ConstraintViolationException
        // (different from the jakarta.validation one above). Surfaces as a PersistenceException
        // at the JPA boundary. Without this branch, every race-loser on a unique key and every
        // row that violates @Check becomes a generic 500.
        if (exception instanceof PersistenceException
                || (exception.getCause() != null
                        && exception.getCause() instanceof org.hibernate.exception.ConstraintViolationException)) {
            LOG.warnf("Database constraint violation: %s",
                    exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage());
            return buildResponse(Response.Status.CONFLICT, "CONSTRAINT_VIOLATION",
                    "Database rejected the change: a uniqueness or integrity rule was violated");
        }

        // ── Malformed input that reaches service code (bad UUID, bad enum, etc.) → 400 ──
        // Callers parsing UUIDs from JWT claims or request bodies throw IllegalArgumentException.
        // Currently these fall to the 500 fallback and mask a client error.
        if (exception instanceof IllegalArgumentException) {
            LOG.warnf("Illegal argument: %s", exception.getMessage());
            return buildResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST",
                    "The request contained an invalid value");
        }

        // ── Quarkus security: unauthenticated caller on a secured endpoint (401) ──
        // Quarkus throws AuthenticationFailedException when no/invalid JWT is presented.
        // Without this branch it falls through to the 500 fallback, masking auth failures
        // as server errors.
        if (exception instanceof AuthenticationFailedException) {
            LOG.warnf("Authentication failed: %s", exception.getMessage());
            return buildResponse(Response.Status.UNAUTHORIZED, "UNAUTHORIZED",
                    "Authentication required");
        }

        // ── JAX-RS 401 (NotAuthorizedException extends WebApplicationException) ──
        if (exception instanceof NotAuthorizedException) {
            LOG.warnf("Not authorized: %s", exception.getMessage());
            return buildResponse(Response.Status.UNAUTHORIZED, "UNAUTHORIZED",
                    "Authentication required");
        }

        // ── Forbidden (403) — authenticated but lacks the required role ──
        // Both jakarta.ws.rs.ForbiddenException and io.quarkus.security.ForbiddenException
        // extend WebApplicationException; handle them explicitly so clients see 403 instead of 500.
        if (exception instanceof jakarta.ws.rs.ForbiddenException
                || exception instanceof io.quarkus.security.ForbiddenException) {
            LOG.warnf("Forbidden: %s", exception.getMessage());
            return buildResponse(Response.Status.FORBIDDEN, "FORBIDDEN",
                    "You do not have permission to perform this action");
        }

        // ── JAX-RS Not Found (404) — unmatched route ──
        if (exception instanceof NotFoundException) {
            LOG.warnf("Not found: %s", exception.getMessage());
            return buildResponse(Response.Status.NOT_FOUND, "NOT_FOUND",
                    "The requested resource was not found");
        }

        // ── JAX-RS Method Not Allowed (405) ──
        if (exception instanceof NotAllowedException nae) {
            String msg = "HTTP method is not supported for this endpoint";
            LOG.warnf("Method not allowed: %s", nae.getMessage());
            return buildResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", msg);
        }

        // ── JAX-RS Unsupported Media Type (415) ──
        if (exception instanceof NotSupportedException nse) {
            String msg = "Media type is not supported for this endpoint";
            LOG.warnf("Not supported: %s", nse.getMessage());
            return buildResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", msg);
        }

        // ── Any other WebApplicationException: preserve its declared status code ──
        // Catches things like ClientErrorException(409) or custom WAE subclasses instead of
        // collapsing them to 500.
        if (exception instanceof WebApplicationException wae) {
            int status = wae.getResponse() != null ? wae.getResponse().getStatus() : 500;
            Response.Status resolved = Response.Status.fromStatusCode(status);
            if (resolved == null) {
                return buildResponse(Response.Status.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                        exception.getMessage());
            }
            LOG.warnf("Web application exception [%d]: %s", status, exception.getMessage());
            return buildResponse(resolved, resolved.name(), exception.getMessage());
        }

        // ── Fallback: unknown/unhandled exceptions → 500 ──
        LOG.errorf(exception, "Unexpected error: %s", exception.getMessage());
        return buildResponse(Response.Status.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please try again later.");
    }

    private Response buildResponse(Response.Status status, String error, String message) {
        ErrorResponse body = ErrorResponse.builder()
                .status(status.getStatusCode())
                .error(error)
                .message(message)
                .build();
        return Response.status(status).entity(body).build();
    }
}
