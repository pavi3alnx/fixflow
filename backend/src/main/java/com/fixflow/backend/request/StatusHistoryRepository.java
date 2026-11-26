package com.fixflow.backend.request;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, Long> {
    org.springframework.data.domain.Page<StatusHistory> findByRequestId(Long requestId,
            org.springframework.data.domain.Pageable pageable);
    java.util.Optional<StatusHistory> findFirstByRequestIdOrderByChangedAtDescIdDesc(Long requestId);
}
