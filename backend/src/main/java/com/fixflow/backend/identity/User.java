package com.fixflow.backend.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", schema = "public")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "student_staff_id", nullable = false, length = 50)
    private String studentStaffId;

    @Column(name = "department", nullable = false, length = 100)
    private String department;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "administratively_disabled", nullable = false)
    private boolean administrativelyDisabled;

    @Column(name="token_version",nullable=false)
    private long tokenVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @PreUpdate
    protected void touch() {
        var now=LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt=now.isAfter(updatedAt)?now:updatedAt.plus(1,java.time.temporal.ChronoUnit.MICROS);
    }
}
