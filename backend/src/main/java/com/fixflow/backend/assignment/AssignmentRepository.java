package com.fixflow.backend.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    boolean existsByRequestIdAndTechnicianIdAndEndedAtIsNull(Long requestId, Long technicianId);
}
