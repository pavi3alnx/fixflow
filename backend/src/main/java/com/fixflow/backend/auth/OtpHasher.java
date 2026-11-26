package com.fixflow.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class OtpHasher {
    private final SecretKeySpec key;
    public OtpHasher(AuthSettings settings) {
        key = new SecretKeySpec(decodeKey(settings.getOtpSecret()), "HmacSHA256");
    }
    public static byte[] decodeKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 32) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Authentication secrets must be Base64-encoded random keys of at least 32 bytes");
        }
    }
    public String hash(Long userId, String email, String nonce, String otp) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    (userId + "\n" + email + "\n" + nonce + "\n" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("OTP hashing unavailable", e); }
    }
    public boolean matches(Long userId, String email, String stored, String otp) {
        var parts = stored.split(":", 2);
        return parts.length == 2 && MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),
                hash(userId, email, parts[0], otp).getBytes(StandardCharsets.UTF_8));
    }
}
