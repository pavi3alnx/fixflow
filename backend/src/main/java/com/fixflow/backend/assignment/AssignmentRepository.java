package com.fixflow.backend.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    boolean existsByRequestIdAndTechnicianIdAndEndedAtIsNull(Long requestId, Long technicianId);
    boolean existsByRequestIdAndEndedAtIsNull(Long requestId);
    boolean existsByRequestIdAndEndReasonAndResolvedAtIsNotNull(Long requestId, AssignmentEndReason reason);
    boolean existsByTechnicianIdAndEndedAtIsNull(Long technicianId);
    java.util.Optional<Assignment> findByRequestIdAndEndedAtIsNull(Long requestId);
    org.springframework.data.domain.Page<Assignment> findByRequestId(Long requestId, org.springframework.data.domain.Pageable pageable);
    java.util.Optional<Assignment> findFirstByRequestIdOrderByAssignedAtDescIdDesc(Long requestId);
    default boolean hasLatestResolution(Long requestId) {
        return findFirstByRequestIdOrderByAssignedAtDescIdDesc(requestId)
                .filter(a -> a.getEndReason()==AssignmentEndReason.RESOLVED && a.getResolvedAt()!=null && a.getEndedAt()!=null).isPresent();
    }
}
