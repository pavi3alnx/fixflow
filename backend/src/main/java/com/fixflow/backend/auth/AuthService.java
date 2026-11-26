package com.fixflow.backend.auth;

import com.fixflow.backend.identity.*;
import com.fixflow.backend.auth.AuthContracts.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final EmailVerificationRepository verifications;
    private final PasswordEncoder passwords;
    private final OtpHasher hasher;
    private final VerificationMailer mailer;
    private final AuthSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RoleRepository roles, EmailVerificationRepository verifications,
                       PasswordEncoder passwords, OtpHasher hasher, VerificationMailer mailer,
                       AuthSettings settings, Clock clock) {
        this.users = users; this.roles = roles; this.verifications = verifications;
        this.passwords = passwords; this.hasher = hasher; this.mailer = mailer;
        this.settings = settings; this.clock = clock;
    }
    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    @Transactional
    public void register(Registration input) {
        String email = normalizeEmail(input.email());
        String domain = email.substring(email.lastIndexOf('@') + 1);
        if (settings.getUniversityDomains().stream().noneMatch(d -> d.strip().equalsIgnoreCase(domain)))
            throw new AuthProblem(HttpStatus.BAD_REQUEST, "Email must belong to a configured university domain.");
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AuthProblem(HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes.");
        String passwordHash = passwords.encode(input.password());
        if (users.existsByEmail(email) || users.existsByStudentStaffId(input.studentStaffId().strip())) return;
        var user = new User();
        user.setFullName(input.fullName().strip()); user.setEmail(email);
        user.setStudentStaffId(input.studentStaffId().strip()); user.setDepartment(input.department().strip());
        user.setPasswordHash(passwordHash);
        user.setRole(roles.findByName("REPORTER").orElseThrow());
        users.saveAndFlush(user);
        issue(user);
    }

    @Transactional
    public void resend(String address) {
        var user = users.lockByEmail(normalizeEmail(address)).orElse(null);
        if (user == null || user.isEmailVerified()) return;
        var previous = verifications.findFirstByUserIdOrderByCreatedAtDescIdDesc(user.getId()).orElse(null);
        if (previous != null && clock.instant().isBefore(previous.getResendAvailableAt())) return;
        issue(user);
    }

    // The user row lock serializes verification and resend, including the no-token case.
    // AuthProblem deliberately commits attempt counts and lockouts before returning an HTTP error.
    @Transactional(noRollbackFor = AuthProblem.class)
    public void verify(Verification input) {
        var user = users.lockByEmail(normalizeEmail(input.email())).orElseThrow(AuthProblem::invalidOtp);
        var token = verifications.findFirstByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .orElseThrow(AuthProblem::invalidOtp);
        var now = clock.instant();
        if (user.isEmailVerified() || token.getConsumedAt() != null || token.getInvalidatedAt() != null
                || !now.isBefore(token.getExpiresAt()) || token.getAttemptCount() >= token.getMaxAttempts())
            throw AuthProblem.invalidOtp();
        token.setAttemptCount(token.getAttemptCount() + 1);
        if (!hasher.matches(user.getId(), user.getEmail(), token.getOtpHash(), input.otp())) {
            if (token.getAttemptCount().equals(token.getMaxAttempts())) token.setInvalidatedAt(now);
            throw AuthProblem.invalidOtp();
        }
        token.setConsumedAt(now);
        user.setEmailVerified(true); user.setEnabled(true);
    }

    private void issue(User user) {
        var now = clock.instant();
        var old = verifications.findFirstByUserIdOrderByCreatedAtDescIdDesc(user.getId()).orElse(null);
        if (old != null && old.getConsumedAt() == null && old.getInvalidatedAt() == null) {
            old.setInvalidatedAt(now);
            verifications.flush(); // Clear the partial unique index before inserting the replacement.
        }
        String otp;
        do { otp = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
        while (old != null && hasher.matches(user.getId(), user.getEmail(), old.getOtpHash(), otp));
        String nonce = UUID.randomUUID().toString();
        var token = new EmailVerification();
        token.setUser(user); token.setEmail(user.getEmail()); token.setCreatedAt(now);
        token.setExpiresAt(now.plus(settings.getOtpTtl()));
        token.setResendAvailableAt(now.plus(settings.getResendCooldown()));
        token.setMaxAttempts(settings.getMaxOtpAttempts());
        token.setOtpHash(nonce + ":" + hasher.hash(user.getId(), user.getEmail(), nonce, otp));
        verifications.saveAndFlush(token);
        try { mailer.send(user.getEmail(), otp); }
        catch (MailException e) {
            // Transaction rolls back the new account or replacement; retry remains possible.
            throw new AuthProblem(HttpStatus.SERVICE_UNAVAILABLE, "Verification email could not be sent. Please retry.");
        }
    }
}
