package com.ebook.common.exception;

import com.ebook.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotSupportedException;
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
