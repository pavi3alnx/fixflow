package com.fixflow.backend.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Notification> {
    long countByRecipientIdAndReadAtIsNull(Long id);
    boolean existsByIdAndRecipientId(Long id,Long recipientId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Notification n set n.readAt=coalesce(n.readAt,case when n.createdAt>:now then n.createdAt else :now end) where n.id=:id and n.recipient.id=:recipient")
    int markRead(Long id,Long recipient,java.time.Instant now);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Notification n set n.readAt=case when n.createdAt>:now then n.createdAt else :now end where n.recipient.id=:recipient and n.readAt is null")
    int markAll(Long recipient,java.time.Instant now);
}
