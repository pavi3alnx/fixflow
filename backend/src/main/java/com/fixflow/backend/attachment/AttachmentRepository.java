package com.fixflow.backend.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    long countByRequestId(Long id);
    java.util.Optional<Attachment> findByIdAndRequestId(Long id,Long requestId);
    org.springframework.data.domain.Page<Attachment> findByRequestId(Long id,org.springframework.data.domain.Pageable pageable);
}
