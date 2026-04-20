package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class ConflictException extends ApplicationException {

    public ConflictException(String message) {
        super(message, "CONFLICT", Response.Status.CONFLICT);
    }
}
