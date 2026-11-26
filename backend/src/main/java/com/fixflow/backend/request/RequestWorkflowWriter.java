package com.fixflow.backend.request;

import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.UserRepository;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Internal persistence helper; callers own role checks, policy validation and the transaction. */
@Component
public class RequestWorkflowWriter {
    private final MaintenanceRequestRepository requests;
    private final StatusHistoryRepository history;
    private final UserRepository users;
    public RequestWorkflowWriter(MaintenanceRequestRepository requests, StatusHistoryRepository history, UserRepository users) {
        this.requests = requests; this.history = history; this.users = users;
    }
    public MaintenanceRequest locked(long id, Instant expected) {
        var request = locked(id); checkExpected(request,expected);
        return request;
    }
    public MaintenanceRequest locked(long id) { return requests.lockById(id).orElseThrow(ResourceNotFoundException::new); }
    public void checkExpected(MaintenanceRequest request,Instant expected) {
        if (!request.getUpdatedAt().equals(expected)) throw new ConflictException("The request has changed. Reload it and retry with its current updatedAt.");
    }
    public long actorId() { return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName()); }
    public Instant touch(MaintenanceRequest request) {
        request.setUpdatedAt(request.getUpdatedAt().plus(1, java.time.temporal.ChronoUnit.MICROS));
        requests.flush(); return request.getUpdatedAt();
    }
    public void transition(MaintenanceRequest request, RequestStatus target, String reason) {
        var previous = request.getStatus(); request.setStatus(target); requests.flush();
        var entry = new StatusHistory(); entry.setRequest(request); entry.setPreviousStatus(previous); entry.setNewStatus(target);
        entry.setReason(reason); entry.setChangedBy(users.getReferenceById(actorId())); entry.setChangedAt(request.getUpdatedAt());
        history.saveAndFlush(entry);
    }
}
