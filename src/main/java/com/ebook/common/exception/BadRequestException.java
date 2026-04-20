package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class BadRequestException extends ApplicationException {

    public BadRequestException(String message) {
        super(message, "BAD_REQUEST", Response.Status.BAD_REQUEST);
    }
}
