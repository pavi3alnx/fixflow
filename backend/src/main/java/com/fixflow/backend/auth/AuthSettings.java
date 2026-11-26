package com.fixflow.backend.auth;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("fixflow.auth")
@Validated
@Getter
@Setter
public class AuthSettings {
    @NotBlank private String jwtSecret;
    @NotBlank private String otpSecret;
    @NotBlank private String issuer = "fixflow";
    @NotBlank private String audience = "fixflow-api";
    @NotBlank @Email private String mailFrom;
    private List<String> universityDomains = List.of();
    private List<String> corsOrigins = List.of();
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration otpTtl = Duration.ofMinutes(10);
    private Duration resendCooldown = Duration.ofSeconds(60);
    @Min(1) @Max(10) private int maxOtpAttempts = 5;

    @AssertTrue(message = "Token/OTP lifetimes and resend cooldown must be positive and bounded")
    public boolean isTimingValid() {
        return positive(accessTokenTtl, Duration.ofHours(1))
                && positive(otpTtl, Duration.ofMinutes(30))
                && positive(resendCooldown, otpTtl);
    }
    private boolean positive(Duration value, Duration maximum) {
        return value != null && maximum != null && !value.isNegative() && !value.isZero()
                && value.compareTo(maximum) <= 0;
    }
}
