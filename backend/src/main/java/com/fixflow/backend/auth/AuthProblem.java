package com.fixflow.backend.auth;

import org.springframework.http.HttpStatus;

/** Safe public messages only. Never attach credentials or provider exceptions. */
public class AuthProblem extends RuntimeException {
    private final HttpStatus status;
    public AuthProblem(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static AuthProblem invalidOtp() {
        return new AuthProblem(HttpStatus.BAD_REQUEST, "Verification code is invalid or unavailable.");
    }
}
