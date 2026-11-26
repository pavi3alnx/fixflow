package com.fixflow.backend.request;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<MaintenanceRequest> {
    boolean existsByIdAndReporterId(Long id, Long reporterId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"building", "room", "category"})
    org.springframework.data.domain.Page<MaintenanceRequest> findAll(
            org.springframework.data.jpa.domain.Specification<MaintenanceRequest> specification,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"building", "room", "category"})
    java.util.Optional<MaintenanceRequest> findOne(
            org.springframework.data.jpa.domain.Specification<MaintenanceRequest> specification);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from MaintenanceRequest r where r.id = :id and r.reporter.id = :reporterId")
    java.util.Optional<MaintenanceRequest> lockOwned(Long id, Long reporterId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from MaintenanceRequest r where r.id = :id")
    java.util.Optional<MaintenanceRequest> lockById(Long id);
}
