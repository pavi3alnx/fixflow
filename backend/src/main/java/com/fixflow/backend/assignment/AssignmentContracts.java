package com.fixflow.backend.assignment;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AssignmentContracts {
    private AssignmentContracts() { }
    public record Assign(@NotNull @Positive Long technicianId,@NotNull Instant expectedUpdatedAt,@Size(max=2000) String reason) {
        public Assign { reason=reason==null || reason.isBlank()?null:reason.strip(); }
        @Override public String toString() { return "Assign[redacted]"; }
    }
    public record Action(@NotNull Instant expectedUpdatedAt,@Size(max=10000) String notes) {
        public Action { notes=notes==null || notes.isBlank()?null:notes.strip(); }
        @Override public String toString() { return "Action[redacted]"; }
    }
    public record Resolution(@NotNull Instant expectedUpdatedAt,@NotBlank @Size(min=5,max=10000) String repairNotes,
                             @Size(max=10) List<@NotNull @Positive Long> evidenceAttachmentIds) {
        public Resolution { repairNotes=repairNotes==null?null:repairNotes.strip(); evidenceAttachmentIds=evidenceAttachmentIds==null?List.of():List.copyOf(evidenceAttachmentIds); }
        @Override public String toString() { return "Resolution[redacted]"; }
    }
    public record View(Long id,Long requestId,Long technicianId,Instant assignedAt,Instant acceptedAt,Instant startedAt,
                       Instant resolvedAt,Instant endedAt,AssignmentEndReason endReason,String repairNotes,List<Long> evidenceAttachmentIds) {
        static View from(Assignment a,List<Long> evidence) {
            return new View(a.getId(),a.getRequest().getId(),a.getTechnician().getId(),a.getAssignedAt(),a.getAcceptedAt(),a.getStartedAt(),a.getResolvedAt(),a.getEndedAt(),a.getEndReason(),a.getRepairNotes(),evidence);
        }
        @Override public String toString() { return "AssignmentView[redacted]"; }
    }
    public record AdminView(View assignment,Long assignedBy,String reassignmentReason) {
        @Override public String toString() { return "AssignmentAdminView[redacted]"; }
    }
}
