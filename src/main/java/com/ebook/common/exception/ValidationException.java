package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class ValidationException extends ApplicationException {

    public ValidationException(String message) {
        super(message, "VALIDATION_FAILED", Response.Status.BAD_REQUEST);
    }
}
