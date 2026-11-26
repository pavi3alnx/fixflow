package com.fixflow.backend.auth;

import com.fixflow.backend.auth.AuthContracts.*;
import com.fixflow.backend.identity.UserRepository;
import com.fixflow.backend.security.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {
    private final AuthenticationManager manager;
    private final UserRepository users;
    private final JwtTokens tokens;
    private final AuthRateLimiter limiter;
    public LoginService(AuthenticationManager manager, UserRepository users, JwtTokens tokens, AuthRateLimiter limiter) {
        this.manager = manager; this.users = users; this.tokens = tokens; this.limiter = limiter;
    }
    @Transactional(readOnly = true)
    public TokenResponse login(Login input) {
        String email = AuthService.normalizeEmail(input.email());
        if (!limiter.allow("login:" + email, 10))
            throw new AuthProblem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please retry later.");
        try {
            manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, input.password()));
        } catch (AuthenticationException | IllegalArgumentException e) {
            throw new AuthProblem(HttpStatus.UNAUTHORIZED, "Invalid credentials or account unavailable.");
        }
        return tokens.issue(users.findByEmail(email).orElseThrow());
    }
}
