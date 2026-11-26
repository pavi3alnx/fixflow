package com.fixflow.backend.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    boolean existsByRequestIdAndTechnicianIdAndEndedAtIsNull(Long requestId, Long technicianId);
    boolean existsByRequestIdAndEndedAtIsNull(Long requestId);
    boolean existsByRequestIdAndEndReasonAndResolvedAtIsNotNull(Long requestId, AssignmentEndReason reason);
}
