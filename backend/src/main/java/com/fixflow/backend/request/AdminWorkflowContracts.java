package com.fixflow.backend.request;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AdminWorkflowContracts {
    private AdminWorkflowContracts() { }
    public record Transition(@NotNull RequestStatus status, @Size(max = 2000) String reason,
                             @NotNull Instant expectedUpdatedAt) {
        public Transition { reason = reason == null || reason.isBlank() ? null : reason.strip(); }
        @Override public String toString() { return "Transition[administrative note redacted]"; }
    }
    public record PriorityChange(@NotNull Priority priority, @NotNull Instant expectedUpdatedAt) { }
    public record AuditEntry(Long id, RequestStatus previousStatus, RequestStatus newStatus,
                             Long actorId, String reason, Instant changedAt) {
        static AuditEntry from(StatusHistory history) {
            return new AuditEntry(history.getId(), history.getPreviousStatus(), history.getNewStatus(),
                    history.getChangedBy().getId(), history.getReason(), history.getChangedAt());
        }
        @Override public String toString() { return "AuditEntry[administrative note redacted]"; }
    }
}
