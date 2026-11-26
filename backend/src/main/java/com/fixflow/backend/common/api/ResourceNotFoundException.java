package com.fixflow.backend.common.api;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() { super("Resource not found"); }
}
