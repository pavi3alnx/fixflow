package com.fixflow.backend;

import com.fixflow.backend.auth.*;
import com.fixflow.backend.auth.AuthContracts.*;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthSecurityTests {
    @Test void keysMustBeValidBase64WithAtLeast256Bits() {
        assertThrows(IllegalStateException.class, () -> OtpHasher.decodeKey("not-base64"));
        assertThrows(IllegalStateException.class, () -> OtpHasher.decodeKey(Base64.getEncoder().encodeToString(new byte[16])));
        assertEquals(32, OtpHasher.decodeKey(Base64.getEncoder().encodeToString(new byte[32])).length);
    }
    @Test void keyedHashesAreBoundToUserEmailAndNonce() {
        var settings = new AuthSettings();
        settings.setOtpSecret(Base64.getEncoder().encodeToString(new byte[32]));
        var hasher = new OtpHasher(settings);
        String stored = "nonce:" + hasher.hash(1L, "a@example.invalid", "nonce", "123456");
        assertTrue(hasher.matches(1L, "a@example.invalid", stored, "123456"));
        assertFalse(hasher.matches(2L, "a@example.invalid", stored, "123456"));
        assertFalse(hasher.matches(1L, "b@example.invalid", stored, "123456"));
        assertFalse(hasher.matches(1L, "a@example.invalid", "other" + stored, "123456"));
        assertFalse(hasher.matches(1L, "a@example.invalid", stored, "000000"));
    }
    @Test void lifetimesArePositiveAndBounded() {
        var settings = new AuthSettings(); assertTrue(settings.isTimingValid());
        settings.setAccessTokenTtl(Duration.ofHours(2)); assertFalse(settings.isTimingValid());
        settings.setAccessTokenTtl(Duration.ofMinutes(15)); settings.setOtpTtl(Duration.ZERO);
        assertFalse(settings.isTimingValid());
        settings.setOtpTtl(Duration.ofMinutes(1)); settings.setResendCooldown(Duration.ofMinutes(2));
        assertFalse(settings.isTimingValid());
    }
    @Test void credentialDtosRedactStringRepresentations() {
        assertFalse(new Registration("name", "email", "id", "department", "secret-password").toString().contains("secret-password"));
        assertFalse(new Login("email", "secret-password").toString().contains("secret-password"));
        assertFalse(new Verification("email", "123456").toString().contains("123456"));
        assertFalse(new TokenResponse("secret-token", "Bearer", 900, null).toString().contains("secret-token"));
    }
}
