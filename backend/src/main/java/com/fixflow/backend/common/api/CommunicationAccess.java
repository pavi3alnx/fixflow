package com.fixflow.backend.common.api;

import com.fixflow.backend.request.*;
import com.fixflow.backend.security.RequestAccess;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Call inside a service transaction. Writes lock before checking mutable assignment visibility. */
@Component
public class CommunicationAccess {
    private final MaintenanceRequestRepository requests;
    private final RequestAccess access;
    public CommunicationAccess(MaintenanceRequestRepository requests,RequestAccess access) { this.requests=requests;this.access=access; }
    public long actorId() { return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName()); }
    public boolean hasRole(String role) { return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+role)); }
    public MaintenanceRequest require(long id,boolean write) {
        var request=write?requests.lockById(id).orElseThrow(ResourceNotFoundException::new)
                :requests.findOne(access.scope(SecurityContextHolder.getContext().getAuthentication()).and((root,q,b)->b.equal(root.get("id"),id))).orElseThrow(ResourceNotFoundException::new);
        if(write && !access.canRead(SecurityContextHolder.getContext().getAuthentication(),id)) throw new ResourceNotFoundException();
        return request;
    }
}
