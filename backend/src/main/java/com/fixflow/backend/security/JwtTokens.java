package com.fixflow.backend.security;

import com.fixflow.backend.auth.AuthContracts.*;
import com.fixflow.backend.auth.AuthSettings;
import com.fixflow.backend.identity.User;
import java.time.Clock;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class JwtTokens {
    private final JwtEncoder encoder;
    private final AuthSettings settings;
    private final Clock clock;
    public JwtTokens(JwtEncoder encoder, AuthSettings settings, Clock clock) {
        this.encoder = encoder; this.settings = settings; this.clock = clock;
    }
    public TokenResponse issue(User user) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(settings.getIssuer()).audience(List.of(settings.getAudience()))
                .subject(user.getId().toString()).issuedAt(now).expiresAt(now.plus(settings.getAccessTokenTtl()))
                .claim("role", user.getRole().getName()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", settings.getAccessTokenTtl().toSeconds(), UserResponse.from(user));
    }
}
