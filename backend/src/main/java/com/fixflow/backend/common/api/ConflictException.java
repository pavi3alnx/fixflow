package com.fixflow.backend.common.api;

/** Business conflicts with messages safe for public API responses. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
