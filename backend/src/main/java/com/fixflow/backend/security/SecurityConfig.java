package com.fixflow.backend.security;

import com.fixflow.backend.auth.*;
import com.fixflow.backend.identity.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.*;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthSettings.class)
public class SecurityConfig {
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean AuthenticationManager authenticationManager(AccountDetailsService details, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        // Check disabled/unverified status after hashing, to reduce account-state timing differences.
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) throw new DisabledException("Account unavailable");
        });
        return new ProviderManager(provider);
    }
    private SecretKeySpec key(AuthSettings settings) {
        var jwt = OtpHasher.decodeKey(settings.getJwtSecret());
        if (java.security.MessageDigest.isEqual(jwt, OtpHasher.decodeKey(settings.getOtpSecret())))
            throw new IllegalStateException("JWT and OTP keys must be independent");
        return new SecretKeySpec(jwt, "HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(AuthSettings settings) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(settings)));
    }
    @Bean JwtDecoder jwtDecoder(AuthSettings settings, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key(settings)).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            var now = clock.instant();
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(settings.getAudience())
                    && validTokenVersion(jwt)
                    && validSubject(jwt.getSubject())
                    && jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(now)
                    && jwt.getExpiresAt() != null && now.isBefore(jwt.getExpiresAt()) && jwt.getIssuedAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(settings.getAccessTokenTtl()) <= 0
                    && jwt.getClaimAsString("role") != null
                    && List.of("REPORTER", "TECHNICIAN", "ADMIN").contains(jwt.getClaimAsString("role"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid access token claims", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(settings.getIssuer()), claims));
        return decoder;
    }
    private boolean validSubject(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]*")) return false;
        try { return Long.parseLong(subject) > 0; }
        catch (NumberFormatException e) { return false; }
    }
    private boolean validTokenVersion(Jwt jwt) {
        Object value=jwt.getClaim("tokenVersion");
        return value==null || value instanceof Number number && number.longValue()>=0 && number.doubleValue()==number.longValue();
    }
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder, UserRepository users,
                                          AuthSettings settings, AuthRateLimiter limiter) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .cors(c -> c.configurationSource(cors(settings)))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setHeader("WWW-Authenticate", "Bearer");
                            SecurityProblems.write(res, HttpStatus.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((req, res, ex) -> SecurityProblems.write(res, HttpStatus.FORBIDDEN)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/verify-email",
                                "/api/auth/resend-otp", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/categories", "/api/categories/{id}",
                                "/api/buildings", "/api/buildings/{id}", "/api/rooms", "/api/rooms/{id}")
                            .hasAnyRole("REPORTER", "TECHNICIAN", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/buildings", "/api/rooms", "/api/categories").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/buildings/{id}", "/api/rooms/{id}", "/api/categories/{id}")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/maintenance-requests").hasRole("REPORTER")
                        .requestMatchers(HttpMethod.PUT, "/api/maintenance-requests/{id}").hasRole("REPORTER")
                        .requestMatchers(HttpMethod.GET, "/api/maintenance-requests", "/api/maintenance-requests/{id}",
                                "/api/maintenance-requests/{id}/history").hasAnyRole("REPORTER", "ADMIN", "TECHNICIAN")
                        .requestMatchers(HttpMethod.PATCH, "/api/admin/maintenance-requests/{id}/verification",
                                "/api/admin/maintenance-requests/{id}/priority", "/api/admin/maintenance-requests/{id}/status").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/admin/maintenance-requests/{id}/history").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,"/api/admin/technicians","/api/admin/maintenance-requests/{id}/assignments").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT,"/api/admin/technicians/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH,"/api/admin/maintenance-requests/{id}/assignment").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,"/api/admin/technicians","/api/admin/technicians/{id}","/api/admin/technicians/{id}/workload","/api/admin/maintenance-requests/{id}/assignments").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,"/api/maintenance-requests/{id}/assignment").hasAnyRole("ADMIN","TECHNICIAN")
                        .requestMatchers(HttpMethod.GET,"/api/technician/workload").hasRole("TECHNICIAN")
                        .requestMatchers(HttpMethod.PATCH,"/api/technician/maintenance-requests/{id}/accept","/api/technician/maintenance-requests/{id}/start",
                                "/api/technician/maintenance-requests/{id}/hold","/api/technician/maintenance-requests/{id}/resume",
                                "/api/technician/maintenance-requests/{id}/progress","/api/technician/maintenance-requests/{id}/resolve").hasRole("TECHNICIAN")
                        .requestMatchers(HttpMethod.PATCH,"/api/maintenance-requests/{id}/confirmation","/api/maintenance-requests/{id}/reopening").hasRole("REPORTER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setHeader("WWW-Authenticate", "Bearer");
                            SecurityProblems.write(res, HttpStatus.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((req, res, ex) -> SecurityProblems.write(res, HttpStatus.FORBIDDEN))
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(jwt -> {
                            // Recheck account status and role on every request. Disabling users takes immediate effect.
                            var user = users.findById(Long.valueOf(jwt.getSubject())).orElseThrow(
                                    () -> new BadCredentialsException("Invalid account"));
                            Number tokenVersion=jwt.getClaim("tokenVersion");
                            if((tokenVersion==null?0L:tokenVersion.longValue())!=user.getTokenVersion()) throw new BadCredentialsException("Invalid account");
                            if (!user.isEnabled() || !user.isEmailVerified() || user.isAdministrativelyDisabled()
                                    || !user.getRole().getName().equals(jwt.getClaimAsString("role")))
                                throw new BadCredentialsException("Invalid account");
                            var principal = AccountPrincipal.from(user);
                            return new JwtAuthenticationToken(jwt, principal.getAuthorities(), user.getId().toString());
                        })))
                .addFilterBefore(new AuthThrottleFilter(limiter), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    private CorsConfigurationSource cors(AuthSettings settings) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(settings.getCorsOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
