package com.fixflow.backend.notification;

import com.fixflow.backend.common.api.*;
import com.fixflow.backend.request.MaintenanceRequestRepository;
import com.fixflow.backend.security.RequestAccess;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('REPORTER','TECHNICIAN','ADMIN')")
@Transactional(readOnly=true)
public class NotificationService {
    public record View(Long id,Long requestId,String eventType,String title,String message,Instant createdAt,Instant readAt,boolean requestAccessible) { }
    private final NotificationRepository repository;
    private final MaintenanceRequestRepository requests;
    private final RequestAccess access;
    private final CommunicationAccess actor;
    public NotificationService(NotificationRepository repository,MaintenanceRequestRepository requests,RequestAccess access,CommunicationAccess actor) {
        this.repository=repository;this.requests=requests;this.access=access;this.actor=actor;
    }
    public PageResponse<View> list(int page,int size,boolean unread) {
        var result=repository.findAll((org.springframework.data.jpa.domain.Specification<Notification>)(root,q,b)-> {
            var predicate=b.equal(root.get("recipient").get("id"),actor.actorId());
            return unread?b.and(predicate,b.isNull(root.get("readAt"))):predicate;
        },PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","id")));
        var ids=result.getContent().stream().map(n->n.getRequest().getId()).collect(Collectors.toSet());
        Set<Long> visible=ids.isEmpty()?Set.of():requests.findAll(access.scope(SecurityContextHolder.getContext().getAuthentication())
                .and((root,q,b)->root.get("id").in(ids))).stream().map(r->r.getId()).collect(Collectors.toSet());
        return PageResponse.from(result.map(n-> {
            boolean permitted=visible.contains(n.getRequest().getId());
            // Never return stored legacy free text: notifications are safe envelopes, not a request-read bypass.
            return new View(n.getId(),permitted?n.getRequest().getId():null,n.getEventType(),"Maintenance activity",
                    "There is an update to your maintenance activity.",n.getCreatedAt(),n.getReadAt(),permitted);
        }));
    }
    public long unreadCount() { return repository.countByRecipientIdAndReadAtIsNull(actor.actorId()); }
    @Transactional public void read(long id) {
        if(!repository.existsByIdAndRecipientId(id,actor.actorId())) throw new ResourceNotFoundException();
        repository.markRead(id,actor.actorId(),Instant.now());
    }
    @Transactional public void readAll() { repository.markAll(actor.actorId(),Instant.now()); }
}
