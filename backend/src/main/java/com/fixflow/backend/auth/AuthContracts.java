package com.fixflow.backend.auth;

import jakarta.validation.constraints.*;
import com.fixflow.backend.identity.User;

public final class AuthContracts {
    private AuthContracts() { }
    public record Registration(
            @NotBlank @Size(max = 150) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 50) String studentStaffId,
            @NotBlank @Size(max = 100) String department,
            @NotBlank @Size(min = 12, max = 72) String password) {
        @Override public String toString() { return "Registration[redacted]"; }
    }
    public record EmailRequest(@NotBlank @Email @Size(max = 255) String email) { }
    public record Verification(@NotBlank @Email @Size(max = 255) String email,
                               @NotBlank @Pattern(regexp = "[0-9]{6}") String otp) {
        @Override public String toString() { return "Verification[redacted]"; }
    }
    public record Login(@NotBlank @Email @Size(max = 255) String email,
                        @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Message(String message) { }
    public record UserResponse(Long id, String fullName, String email, String department, String role) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getFullName(), user.getEmail(),
                    user.getDepartment(), user.getRole().getName());
        }
    }
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }
}
