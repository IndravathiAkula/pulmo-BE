package com.ebook.common.exception;

import jakarta.ws.rs.core.Response;

public class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException(String message) {
        super(message, "RESOURCE_NOT_FOUND", Response.Status.NOT_FOUND);
    }

    public ResourceNotFoundException(String resource, String identifier) {
        super(resource + " not found with identifier: " + identifier, "RESOURCE_NOT_FOUND", Response.Status.NOT_FOUND);
    }
}
