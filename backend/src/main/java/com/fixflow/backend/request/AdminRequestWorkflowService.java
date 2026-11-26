package com.fixflow.backend.request;

import com.fixflow.backend.assignment.*;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.UserRepository;
import com.fixflow.backend.request.AdminWorkflowContracts.*;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class AdminRequestWorkflowService {
    private final MaintenanceRequestRepository requests;
    private final StatusHistoryRepository history;
    private final AssignmentRepository assignments;
    private final UserRepository users;
    public AdminRequestWorkflowService(MaintenanceRequestRepository requests, StatusHistoryRepository history,
                                        AssignmentRepository assignments, UserRepository users) {
        this.requests = requests; this.history = history; this.assignments = assignments; this.users = users;
    }
    @Transactional
    public RequestResponse verify(long id, Transition input) {
        if (input.status() != RequestStatus.VERIFIED && input.status() != RequestStatus.REJECTED)
            throw new BadRequestException("Verification must select VERIFIED or REJECTED.");
        var request = locked(id, input.expectedUpdatedAt());
        if (request.getStatus() != RequestStatus.OPEN) throw new ConflictException("Only OPEN requests may be reviewed.");
        return transition(request, input);
    }
    @Transactional
    public RequestResponse status(long id, Transition input) {
        var request = locked(id, input.expectedUpdatedAt());
        if (request.getStatus() == RequestStatus.OPEN)
            throw new ConflictException("Use verification to review an OPEN request.");
        return transition(request, input);
    }
    @Transactional
    public RequestResponse priority(long id, PriorityChange input) {
        var request = locked(id, input.expectedUpdatedAt());
        RequestWorkflowPolicy.validatePriority(request.getStatus());
        if (request.getPriority() != input.priority()) {
            request.setPriority(input.priority()); requests.flush();
        }
        // A fresh same-priority write is a no-op: no timestamp change and no fake status history.
        return RequestResponse.from(request);
    }
    public PageResponse<AuditEntry> history(long id, int page, int size) {
        if (!requests.existsById(id)) throw new ResourceNotFoundException();
        return PageResponse.from(history.findByRequestId(id, PageRequest.of(page, size, Sort.by("changedAt", "id")))
                .map(AuditEntry::from));
    }
    private MaintenanceRequest locked(long id, Instant expected) {
        var request = requests.lockById(id).orElseThrow(ResourceNotFoundException::new);
        if (!request.getUpdatedAt().equals(expected))
            throw new ConflictException("The request has changed. Reload it and retry with its current updatedAt.");
        return request;
    }
    private RequestResponse transition(MaintenanceRequest request, Transition input) {
        RequestWorkflowPolicy.validate(request.getStatus(), input.status(), input.reason(),
                assignments.existsByRequestIdAndEndedAtIsNull(request.getId()),
                assignments.existsByRequestIdAndEndReasonAndResolvedAtIsNotNull(request.getId(), AssignmentEndReason.RESOLVED));
        var previous = request.getStatus(); request.setStatus(input.status());
        requests.flush(); // Generates the monotonic, database-precision update timestamp within this transaction.
        var entry = new StatusHistory(); entry.setRequest(request); entry.setPreviousStatus(previous);
        entry.setNewStatus(input.status()); entry.setReason(input.reason());
        entry.setChangedBy(users.getReferenceById(Long.valueOf(SecurityContextHolder.getContext().getAuthentication().getName())));
        entry.setChangedAt(request.getUpdatedAt());
        history.saveAndFlush(entry);
        return RequestResponse.from(request);
    }
}
