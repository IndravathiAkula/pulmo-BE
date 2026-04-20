package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class UnauthorizedException extends ApplicationException {

    public UnauthorizedException(String message) {
        super(message, "UNAUTHORIZED", Response.Status.UNAUTHORIZED);
    }
}
