package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class ForbiddenException extends ApplicationException {

    public ForbiddenException(String message) {
        super(message, "FORBIDDEN", Response.Status.FORBIDDEN);
    }
}
