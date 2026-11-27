package com.fixflow.backend.comment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths="author.role")
    org.springframework.data.domain.Page<Comment> findByRequestId(Long id,org.springframework.data.domain.Pageable page);
}
