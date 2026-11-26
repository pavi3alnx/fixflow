package com.fixflow.backend.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
    java.util.Optional<Role> findByName(String name);
}
