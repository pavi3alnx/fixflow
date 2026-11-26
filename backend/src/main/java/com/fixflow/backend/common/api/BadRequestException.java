package com.fixflow.backend.common.api;

/** Business validation failures with messages safe for public responses. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
}
