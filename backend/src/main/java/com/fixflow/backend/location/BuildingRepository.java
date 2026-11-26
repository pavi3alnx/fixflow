package com.fixflow.backend.location;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Building> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from Building b where b.id = :id")
    java.util.Optional<Building> lockById(Long id);
}
