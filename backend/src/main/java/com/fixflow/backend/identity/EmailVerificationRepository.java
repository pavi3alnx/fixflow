package com.fixflow.backend.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {
    java.util.Optional<EmailVerification> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
