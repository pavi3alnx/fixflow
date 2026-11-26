package com.fixflow.backend.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthThrottleFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    public AuthThrottleFilter(AuthRateLimiter limiter) { this.limiter = limiter; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (request.getMethod().equals("POST") && path.startsWith("/api/auth/")) {
            response.setHeader("Cache-Control", "no-store");
            // Never trust caller-supplied X-Forwarded-For without a configured trusted proxy.
            if (!limiter.allow("ip:" + request.getRemoteAddr(), 30)) {
                response.setHeader("Retry-After", "60");
                SecurityProblems.write(response, HttpStatus.TOO_MANY_REQUESTS); return;
            }
        }
        chain.doFilter(request, response);
    }
}
