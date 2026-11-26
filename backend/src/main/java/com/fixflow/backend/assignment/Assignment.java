package com.fixflow.backend.assignment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import com.fixflow.backend.request.MaintenanceRequest;
import com.fixflow.backend.identity.User;

@Entity
@Table(name = "assignments", schema = "public")
@Getter
@Setter
public class Assignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private MaintenanceRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "technician_id", nullable = false)
    private User technician;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "accepted_at", nullable = true)
    private Instant acceptedAt;

    @Column(name = "started_at", nullable = true)
    private Instant startedAt;

    @Column(name = "resolved_at", nullable = true)
    private Instant resolvedAt;

    @Column(name = "repair_notes", nullable = true, columnDefinition = "text")
    private String repairNotes;

    @Column(name = "ended_at", nullable = true)
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", nullable = true, length = 20)
    private AssignmentEndReason endReason;
}
