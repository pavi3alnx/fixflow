package com.fixflow.backend.auth;

import com.fixflow.backend.auth.AuthContracts.*;
import com.fixflow.backend.identity.UserRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    private final LoginService login;
    private final UserRepository users;
    public AuthController(AuthService service, LoginService login, UserRepository users) {
        this.service = service; this.login = login; this.users = users;
    }
    @PostMapping("/register")
    public ResponseEntity<Message> register(@RequestBody @Valid Registration input) {
        try { service.register(input); }
        catch (DataIntegrityViolationException e) {
            // Concurrent duplicate registration has the same public result as an existing account.
            // Only unique violations qualify; other integrity errors must remain visible as safe errors.
            if (!(e.getMostSpecificCause() instanceof java.sql.SQLException sql) || !"23505".equals(sql.getSQLState())) throw e;
        }
        return accepted();
    }
    @PostMapping("/resend-otp")
    public ResponseEntity<Message> resend(@RequestBody @Valid EmailRequest input) {
        service.resend(input.email()); return accepted();
    }
    private ResponseEntity<Message> accepted() {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(
                new Message("If the account is eligible, a verification email will be sent."));
    }
    @PostMapping("/verify-email")
    public Message verify(@RequestBody @Valid Verification input) {
        service.verify(input); return new Message("Email verified. You may now log in.");
    }
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid Login input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login.login(input));
    }
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('REPORTER', 'TECHNICIAN', 'ADMIN')")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                UserResponse.from(users.findById(Long.valueOf(authentication.getName())).orElseThrow()));
    }
}
