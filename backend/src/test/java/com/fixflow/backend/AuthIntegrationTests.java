package com.fixflow.backend;

import com.fixflow.backend.auth.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.identity.Role;
import com.fixflow.backend.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AuthIntegrationTests.Probes.class)
class AuthIntegrationTests {
    private static final TestDatabase DATABASE = database();
    private static final AtomicLong MINUTES = new AtomicLong();
    private static final String PASSWORD = "Safe-password-123!";
    private static TestDatabase database() {
        try { return new TestDatabase(); }
        catch (java.sql.SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.username);
        registry.add("spring.datasource.password", () -> DATABASE.password);
        registry.add("fixflow.auth.cors-origins", () -> "http://localhost:5173");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired EmailVerificationRepository verifications;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired AuthSettings settings;
    @Autowired AuthService auth;
    @Autowired JwtTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired GuardProbe guard;
    @Autowired AuthRateLimiter limiter;
    @MockitoBean JavaMailSender sender;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final Queue<SimpleMailMessage> messages = new ConcurrentLinkedQueue<>();

    @BeforeEach void setup() {
        now.set(Instant.now().plusSeconds(MINUTES.addAndGet(120) * 60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        when(clock.instant()).thenAnswer(inv -> now.get());
        when(clock.millis()).thenAnswer(inv -> now.get().toEpochMilli());
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; }).when(sender).send(any(SimpleMailMessage.class));
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }
    private String email() { return UUID.randomUUID() + "@university.example"; }
    private ResultActions postJson(String endpoint, Object body) throws Exception {
        return mvc.perform(post("/api/auth/" + endpoint).contentType("application/json").content(json.writeValueAsString(body)));
    }
    private Map<String, Object> registration(String email, String id) {
        return new HashMap<>(Map.of("fullName", "Test Reporter", "email", email, "studentStaffId", id,
                "department", "Engineering", "password", PASSWORD));
    }
    private void register(String email) throws Exception {
        postJson("register", registration(email, UUID.randomUUID().toString())).andExpect(status().isAccepted());
    }
    private String otp(String email) {
        var message = messages.stream().filter(m -> Arrays.asList(m.getTo()).contains(email)).reduce((a,b) -> b).orElseThrow();
        var match = Pattern.compile("\\b[0-9]{6}\\b").matcher(message.getText());
        assertTrue(match.find()); return match.group();
    }
    private ResultActions verifyCode(String email, String code) throws Exception {
        return postJson("verify-email", Map.of("email", email, "otp", code));
    }
    private String activate(String email) throws Exception {
        register(email); verifyCode(email, otp(email)).andExpect(status().isOk());
        return login(email);
    }
    private String login(String email) throws Exception {
        String response = postJson("login", Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("tokenType").value("Bearer"))
                .andExpect(jsonPath("user.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asText();
    }
    private EmailVerification latest(String email) {
        return verifications.findFirstByUserIdOrderByCreatedAtDescIdDesc(users.findByEmail(email).orElseThrow().getId()).orElseThrow();
    }
    private String customToken(String subject, String role, Instant issued, Instant expires, String issuer, String audience) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).audience(List.of(audience))
                .issuedAt(issued).expiresAt(expires).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    private void authenticate(Long id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                id.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test void registrationNormalizesAndHashesWithoutGrantingPrivileges() throws Exception {
        String email = email();
        var body = registration(email.toUpperCase(Locale.ROOT), UUID.randomUUID().toString());
        body.put("role", "ADMIN"); body.put("enabled", true); body.put("emailVerified", true);
        postJson("register", body).andExpect(status().isAccepted()).andExpect(jsonPath("passwordHash").doesNotExist());
        var user = users.findByEmail(email).orElseThrow();
        assertEquals("REPORTER", user.getRole().getName());
        assertFalse(user.isEnabled()); assertFalse(user.isEmailVerified());
        assertNotEquals(PASSWORD, user.getPasswordHash()); assertTrue(passwords.matches(PASSWORD, user.getPasswordHash()));
        assertFalse(latest(email).getOtpHash().contains(otp(email)));
        assertEquals(6, otp(email).length());
    }
    @Test void invalidRegistrationIsRejected() throws Exception {
        var body = registration("someone@outside.example", UUID.randomUUID().toString());
        postJson("register", body).andExpect(status().isBadRequest());
        body.put("email", email()); body.put("password", "short");
        postJson("register", body).andExpect(status().isBadRequest());
        body.put("password", "é".repeat(40));
        postJson("register", body).andExpect(status().isBadRequest());
        body.put("password", PASSWORD); body.put("fullName", " ");
        postJson("register", body).andExpect(status().isBadRequest());
    }
    @Test void duplicateEmailAndStudentIdHaveGenericResponseAndNoExtraAccounts() throws Exception {
        String email = email(), id = UUID.randomUUID().toString();
        var body = registration(email, id);
        postJson("register", body).andExpect(status().isAccepted());
        long count = users.count();
        postJson("register", registration(email.toUpperCase(Locale.ROOT), UUID.randomUUID().toString())).andExpect(status().isAccepted());
        postJson("register", registration(email(), id)).andExpect(status().isAccepted());
        assertEquals(count, users.count()); assertEquals(1, messages.size());
    }
    @Test void successfulVerificationActivatesAndPreventsReuse() throws Exception {
        String email = email(); register(email);
        verifyCode(email, otp(email)).andExpect(status().isOk());
        var user = users.findByEmail(email).orElseThrow();
        assertTrue(user.isEnabled()); assertTrue(user.isEmailVerified());
        assertNotNull(latest(email).getConsumedAt());
        verifyCode(email, otp(email)).andExpect(status().isBadRequest());
    }
    @Test void incorrectAttemptsPersistAndLockOutEvenTheCorrectOtp() throws Exception {
        String email = email(); register(email);
        String wrong = otp(email).equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= settings.getMaxOtpAttempts(); i++) {
            verifyCode(email, wrong).andExpect(status().isBadRequest());
            assertEquals(i, latest(email).getAttemptCount());
        }
        assertNotNull(latest(email).getInvalidatedAt());
        verifyCode(email, otp(email)).andExpect(status().isBadRequest());
        assertFalse(users.findByEmail(email).orElseThrow().isEnabled());
    }
    @Test void expiredOtpCannotActivateAnAccount() throws Exception {
        String email = email(); register(email);
        now.set(latest(email).getExpiresAt());
        verifyCode(email, otp(email)).andExpect(status().isBadRequest());
        assertFalse(users.findByEmail(email).orElseThrow().isEmailVerified());
    }
    @Test void resendEnforcesCooldownAndInvalidatesOldCode() throws Exception {
        String email = email(); register(email);
        var old = latest(email); String oldCode = otp(email);
        postJson("resend-otp", Map.of("email", email)).andExpect(status().isAccepted());
        assertEquals(1, messages.size());
        now.set(old.getResendAvailableAt());
        postJson("resend-otp", Map.of("email", email)).andExpect(status().isAccepted());
        assertEquals(2, messages.size());
        assertNotNull(verifications.findById(old.getId()).orElseThrow().getInvalidatedAt());
        assertNotEquals(old.getId(), latest(email).getId());
        // Replacement also avoids immediately repeating the old six-digit code.
        assertNotEquals(old.getOtpHash(), latest(email).getOtpHash());
        assertNotEquals(oldCode, otp(email));
        verifyCode(email, oldCode).andExpect(status().isBadRequest());
        verifyCode(email, otp(email)).andExpect(status().isOk());
    }
    @Test void unknownAndVerifiedResendsAreGenericAndSendNothing() throws Exception {
        postJson("resend-otp", Map.of("email", email())).andExpect(status().isAccepted());
        assertTrue(messages.isEmpty());
        String email = email(); activate(email); messages.clear();
        postJson("resend-otp", Map.of("email", email)).andExpect(status().isAccepted());
        assertTrue(messages.isEmpty());
    }
    @Test void mailFailuresRollbackRegistrationAndAllowRetry() throws Exception {
        String email = email();
        doThrow(new MailSendException("synthetic SMTP detail")).when(sender).send(any(SimpleMailMessage.class));
        postJson("register", registration(email, UUID.randomUUID().toString())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("detail").value("Verification email could not be sent. Please retry."));
        assertTrue(users.findByEmail(email).isEmpty());
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; }).when(sender).send(any(SimpleMailMessage.class));
        register(email); verifyCode(email, otp(email)).andExpect(status().isOk());
    }
    @Test void failedReplacementPreservesPreviousOtpAndAllowsRetry() throws Exception {
        String email = email(); register(email); var old = latest(email);
        now.set(old.getResendAvailableAt());
        doThrow(new MailSendException("synthetic")).when(sender).send(any(SimpleMailMessage.class));
        postJson("resend-otp", Map.of("email", email)).andExpect(status().isServiceUnavailable());
        assertEquals(old.getId(), latest(email).getId()); assertNull(latest(email).getInvalidatedAt());
        verifyCode(email, otp(email)).andExpect(status().isOk());
    }
    @Test void concurrentVerificationConsumesExactlyOnce() throws Exception {
        String email = email(); register(email); String otp = otp(email);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> verify = () -> {
                start.await();
                try { auth.verify(new AuthContracts.Verification(email, otp)); return true; }
                catch (AuthProblem e) { return false; }
            };
            var first = pool.submit(verify); var second = pool.submit(verify); start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertTrue(users.findByEmail(email).orElseThrow().isEnabled());
    }
    @Test void concurrentResendsCreateOnlyOneReplacement() throws Exception {
        String email = email(); register(email); now.set(latest(email).getResendAvailableAt());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Void> resend = () -> { start.await(); auth.resend(email); return null; };
            var first = pool.submit(resend); var second = pool.submit(resend); start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, messages.size());
    }
    @Test void concurrentVerifyAndResendLeaveConsistentAccountAndTokenState() throws Exception {
        String email = email(); register(email); String code = otp(email);
        now.set(latest(email).getResendAvailableAt());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var verify = pool.submit(() -> {
                start.await();
                try { auth.verify(new AuthContracts.Verification(email, code)); return true; }
                catch (AuthProblem e) { return false; }
            });
            var resend = pool.submit(() -> { start.await(); auth.resend(email); return null; });
            start.countDown();
            boolean verified = verify.get(10, TimeUnit.SECONDS); resend.get(10, TimeUnit.SECONDS);
            assertEquals(verified, users.findByEmail(email).orElseThrow().isEmailVerified());
            if (verified) {
                assertNotNull(latest(email).getConsumedAt()); assertEquals(1, messages.size());
            } else {
                assertNull(latest(email).getConsumedAt()); assertEquals(2, messages.size());
                verifyCode(email, otp(email)).andExpect(status().isOk());
            }
        }
    }
    @Test void invalidAndUnverifiedLoginHaveTheSameSafeError() throws Exception {
        String email = email(); register(email);
        for (var body : List.of(Map.of("email", email, "password", PASSWORD),
                Map.of("email", email(), "password", PASSWORD))) {
            postJson("login", body).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("detail").value("Invalid credentials or account unavailable."));
        }
        verifyCode(email, otp(email)).andExpect(status().isOk());
        postJson("login", Map.of("email", email, "password", "wrong-password"))
                .andExpect(status().isUnauthorized());
    }
    @Test void validJwtCarriesClaimsAndAuthenticatesProtectedEndpoints() throws Exception {
        String email = email(), token = activate(email);
        Jwt jwt = decoder.decode(token);
        assertEquals(users.findByEmail(email).orElseThrow().getId().toString(), jwt.getSubject());
        assertEquals("REPORTER", jwt.getClaimAsString("role"));
        assertEquals(settings.getAccessTokenTtl(), Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("email").value(email)).andExpect(jsonPath("passwordHash").doesNotExist());
        mvc.perform(get("/api/categories/1").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/categories/1")).andExpect(status().isUnauthorized());
    }
    @Test void expiredTamperedAndMalformedTokensAreRejected() throws Exception {
        String token = activate(email());
        now.set(now.get().plus(settings.getAccessTokenTtl()));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String[] pieces = token.split("\\.");
        String badSignature = (pieces[2].charAt(0) == 'A' ? "B" : "A") + pieces[2].substring(1);
        for (String bad : List.of(pieces[0] + "." + pieces[1] + "." + badSignature, "not-a-jwt"))
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized());
    }
    @Test void wrongIssuerAudienceRoleAndFutureIssuedTokensAreRejected() throws Exception {
        String email = email(); activate(email);
        String id = users.findByEmail(email).orElseThrow().getId().toString();
        var issued = now.get(); var expires = issued.plusSeconds(60);
        for (String token : List.of(
                customToken(id, "REPORTER", issued, expires, "wrong", settings.getAudience()),
                customToken(id, "REPORTER", issued, expires, settings.getIssuer(), "wrong"),
                customToken(id, "SUPERUSER", issued, expires, settings.getIssuer(), settings.getAudience()),
                customToken(id, "REPORTER", issued.plusSeconds(10), expires, settings.getIssuer(), settings.getAudience())))
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void disablingOrChangingRoleInvalidatesExistingTokens() throws Exception {
        String email = email(), token = activate(email);
        var user = users.findByEmail(email).orElseThrow();
        user.setEnabled(false); users.saveAndFlush(user);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        postJson("login", Map.of("email", email, "password", PASSWORD)).andExpect(status().isUnauthorized());
        user.setEnabled(true);
        user.setRole(role("ADMIN"));
        users.saveAndFlush(user);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void tokensMissingRequiredClaimsAreRejected() throws Exception {
        String email = email(); activate(email);
        String id = users.findByEmail(email).orElseThrow().getId().toString();
        for (String missing : List.of("iss", "aud", "sub", "iat", "exp", "role")) {
            var claims = JwtClaimsSet.builder().subject(id).issuer(settings.getIssuer())
                    .audience(List.of(settings.getAudience())).issuedAt(now.get())
                    .expiresAt(now.get().plusSeconds(60)).claim("role", "REPORTER")
                    .claims(values -> values.remove(missing)).build();
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
    }
    private Role role(String name) {
        var role = new Role(); role.setName(name);
        role.setId(jdbc.queryForObject("select id from roles where name=?", Long.class, name)); return role;
    }
    @Test void allThreeRolesCanAccessMeButUnspecifiedAndProvisioningRoutesAreDenied() throws Exception {
        String email = email(); activate(email);
        var user = users.findByEmail(email).orElseThrow();
        for (String role : List.of("REPORTER", "TECHNICIAN", "ADMIN")) {
            user.setRole(role(role)); users.saveAndFlush(user);
            String token = tokens.issue(users.findByEmail(email).orElseThrow()).accessToken();
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/unspecified").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
    }
    @Test void methodSecurityRestrictsAdministrativeOperations() throws Exception {
        String email = email(); activate(email); Long id = users.findByEmail(email).orElseThrow().getId();
        authenticate(id, "REPORTER"); assertThrows(AccessDeniedException.class, () -> guard.adminOnly());
        authenticate(id, "TECHNICIAN"); assertThrows(AccessDeniedException.class, () -> guard.adminOnly());
        authenticate(id, "ADMIN"); assertEquals("allowed", guard.adminOnly());
    }
    @Test void ownershipGuardRejectsOtherReportersAndUnassignedTechnicians() throws Exception {
        String owner = email(), other = email(); activate(owner); activate(other);
        Long ownerId = users.findByEmail(owner).orElseThrow().getId(), otherId = users.findByEmail(other).orElseThrow().getId();
        Long buildingId = jdbc.queryForObject("insert into buildings(code,name) values (?, 'Ownership test') returning id", Long.class, UUID.randomUUID().toString());
        Long requestId = jdbc.queryForObject("insert into maintenance_requests(reporter_id,building_id,category_id,title,description,suggested_priority,priority) values (?,?,1,'Private','Details','LOW','LOW') returning id", Long.class, ownerId, buildingId);
        authenticate(ownerId, "REPORTER"); assertEquals("allowed", guard.read(requestId));
        authenticate(otherId, "REPORTER"); assertThrows(AccessDeniedException.class, () -> guard.read(requestId));
        authenticate(otherId, "TECHNICIAN"); assertThrows(AccessDeniedException.class, () -> guard.read(requestId));
        jdbc.update("insert into assignments(request_id,technician_id,assigned_by) values (?,?,?)", requestId, otherId, ownerId);
        assertEquals("allowed", guard.read(requestId));
        authenticate(otherId, "ADMIN"); assertEquals("allowed", guard.read(requestId));
        assertThrows(AccessDeniedException.class, () -> guard.read(Long.MAX_VALUE));
    }
    @Test void loginAndIpThrottlesReturn429() throws Exception {
        String email = email();
        for (int i = 0; i < 10; i++) postJson("login", Map.of("email", email, "password", PASSWORD)).andExpect(status().isUnauthorized());
        postJson("login", Map.of("email", email, "password", PASSWORD)).andExpect(status().isTooManyRequests());
        for (int i = 0; i < 30; i++)
            mvc.perform(post("/api/auth/resend-otp").with(req -> { req.setRemoteAddr("192.0.2.42"); return req; })
                    .contentType("application/json").content(json.writeValueAsString(Map.of("email", email))))
                    .andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/resend-otp").with(req -> { req.setRemoteAddr("192.0.2.42"); return req; })
                .contentType("application/json").content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        now.set(now.get().plusSeconds(60));
        assertTrue(limiter.allow("login:" + email, 10));
    }
    @Test void corsOnlyAllowsConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/auth/login").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
    @TestConfiguration static class Probes {
        @Bean GuardProbe guardProbe() { return new GuardProbe(); }
    }
    static class GuardProbe {
        @PreAuthorize("hasRole('ADMIN')") public String adminOnly() { return "allowed"; }
        @PreAuthorize("@requestAccess.canRead(authentication, #requestId)")
        public String read(Long requestId) { return "allowed"; }
    }
}
