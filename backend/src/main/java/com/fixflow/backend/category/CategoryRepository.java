package com.fixflow.backend.category;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Category> {
    java.util.Optional<Category> findByCode(String code);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Category c where c.id = :id")
    java.util.Optional<Category> lockById(Long id);
}
