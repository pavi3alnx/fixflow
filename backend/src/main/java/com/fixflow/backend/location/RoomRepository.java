package com.fixflow.backend.location;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Room> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "building")
    org.springframework.data.domain.Page<Room> findAll(org.springframework.data.jpa.domain.Specification<Room> specification,
                                                      org.springframework.data.domain.Pageable pageable);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "building")
    java.util.Optional<Room> findById(Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Room r where r.id = :id")
    java.util.Optional<Room> lockById(Long id);
}
