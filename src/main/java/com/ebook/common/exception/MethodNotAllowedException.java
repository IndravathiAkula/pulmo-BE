package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class MethodNotAllowedException extends ApplicationException {

    public MethodNotAllowedException(String message) {
        super(message, "METHOD_NOT_ALLOWED", Response.Status.METHOD_NOT_ALLOWED);
    }
}
