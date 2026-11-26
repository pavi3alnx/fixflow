package com.fixflow.backend.security;

import com.fixflow.backend.assignment.Assignment;
import com.fixflow.backend.request.MaintenanceRequestRepository;
import com.fixflow.backend.request.MaintenanceRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One SQL visibility policy shared by request details, listings, history and method guards. */
@Component("requestAccess")
public class RequestAccess {
    private final MaintenanceRequestRepository requests;
    public RequestAccess(MaintenanceRequestRepository requests) { this.requests = requests; }
    @Transactional(readOnly = true)
    public boolean canRead(Authentication authentication, Long requestId) {
        if (requestId == null) return false;
        return requests.exists(scope(authentication).and((root, query, builder) -> builder.equal(root.get("id"), requestId)));
    }
    public Specification<MaintenanceRequest> scope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return (root, query, builder) -> builder.disjunction();
        long userId;
        try { userId = Long.parseLong(authentication.getName()); }
        catch (NumberFormatException e) { return (root, query, builder) -> builder.disjunction(); }
        if (hasRole(authentication, "ADMIN")) return (root, query, builder) -> builder.conjunction();
        if (hasRole(authentication, "REPORTER")) return (root, query, builder) -> builder.equal(root.get("reporter").get("id"), userId);
        if (!hasRole(authentication, "TECHNICIAN")) return (root, query, builder) -> builder.disjunction();
        return (root, query, builder) -> {
            var subquery = query.subquery(Long.class);
            var assignment = subquery.from(Assignment.class);
            subquery.select(assignment.get("id")).where(
                    builder.equal(assignment.get("request").get("id"), root.get("id")),
                    builder.equal(assignment.get("technician").get("id"), userId),
                    builder.isNull(assignment.get("endedAt")));
            return builder.exists(subquery);
        };
    }
    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
