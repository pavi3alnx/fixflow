package com.fixflow.backend.security;

import com.fixflow.backend.assignment.AssignmentRepository;
import com.fixflow.backend.request.MaintenanceRequestRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resource guard for future request services; does not expose an FF-05 request API. */
@Component("requestAccess")
public class RequestAccess {
    private final MaintenanceRequestRepository requests;
    private final AssignmentRepository assignments;
    public RequestAccess(MaintenanceRequestRepository requests, AssignmentRepository assignments) {
        this.requests = requests; this.assignments = assignments;
    }
    @Transactional(readOnly = true)
    public boolean canRead(Authentication authentication, Long requestId) {
        if (authentication == null || !authentication.isAuthenticated() || requestId == null) return false;
        long userId;
        try { userId = Long.parseLong(authentication.getName()); }
        catch (NumberFormatException e) { return false; }
        if (hasRole(authentication, "ADMIN")) return requests.existsById(requestId);
        if (hasRole(authentication, "REPORTER")) return requests.existsByIdAndReporterId(requestId, userId);
        return hasRole(authentication, "TECHNICIAN")
                && assignments.existsByRequestIdAndTechnicianIdAndEndedAtIsNull(requestId, userId);
    }
    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
