package com.fixflow.backend.request;

import com.fixflow.backend.assignment.*;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.request.AdminWorkflowContracts.*;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class AdminRequestWorkflowService {
    private final MaintenanceRequestRepository requests;
    private final StatusHistoryRepository history;
    private final AssignmentRepository assignments;
    private final RequestWorkflowWriter writer;
    public AdminRequestWorkflowService(MaintenanceRequestRepository requests, StatusHistoryRepository history,
                                        AssignmentRepository assignments, RequestWorkflowWriter writer) {
        this.requests = requests; this.history = history; this.assignments = assignments;
        this.writer = writer;
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
        return writer.locked(id, expected);
    }
    private RequestResponse transition(MaintenanceRequest request, Transition input) {
        RequestWorkflowPolicy.validate(request.getStatus(), input.status(), input.reason(),
                assignments.existsByRequestIdAndEndedAtIsNull(request.getId()),
                assignments.hasLatestResolution(request.getId()));
        writer.transition(request, input.status(), input.reason());
        return RequestResponse.from(request);
    }
}
