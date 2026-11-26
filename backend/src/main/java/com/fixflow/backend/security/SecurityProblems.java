package com.fixflow.backend.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;

public final class SecurityProblems {
    private SecurityProblems() { }
    public static void write(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\""
                + (status == HttpStatus.UNAUTHORIZED ? "Authentication is required or invalid."
                : status == HttpStatus.FORBIDDEN ? "Access is denied." : "Too many requests. Please retry later.") + "\"}");
    }
}
