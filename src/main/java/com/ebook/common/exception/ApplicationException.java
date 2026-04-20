package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;
import lombok.Getter;

/**
 * Base exception for all application-level errors.
 * Carries an HTTP status and a stable error code for programmatic consumption.
 */
@Getter
public abstract class ApplicationException extends RuntimeException {

    private final String errorCode;
    private final Response.Status httpStatus;

    protected ApplicationException(String message, String errorCode, Response.Status httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    protected ApplicationException(String message, String errorCode, Response.Status httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

}
