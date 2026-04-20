package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class InternalServerException extends ApplicationException {

    public InternalServerException(String message) {
        super(message, "INTERNAL_ERROR", Response.Status.INTERNAL_SERVER_ERROR);
    }

    public InternalServerException(String message, Throwable cause) {
        super(message, "INTERNAL_ERROR", Response.Status.INTERNAL_SERVER_ERROR, cause);
    }
}
