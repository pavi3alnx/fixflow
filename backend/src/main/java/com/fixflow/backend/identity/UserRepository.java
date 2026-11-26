package com.fixflow.backend.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "role")
    java.util.Optional<User> findByEmail(String email);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "role")
    java.util.Optional<User> findById(Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.email = :email")
    java.util.Optional<User> lockByEmail(String email);

    boolean existsByEmail(String email);
    boolean existsByStudentStaffId(String studentStaffId);
}
