package com.fixflow.backend.comment;

import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.UserRepository;
import com.fixflow.backend.notification.NotificationPublisher;
import com.fixflow.backend.request.RequestStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('REPORTER','TECHNICIAN','ADMIN')")
@Transactional(readOnly=true)
public class CommentService {
    public record Input(@NotBlank @Size(max=5000) String body) {
        public Input { body=body==null?null:body.strip(); }
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String name,Object value) { throw new IllegalArgumentException("Only public comment body is supported"); }
        @Override public String toString() { return "CommentInput[redacted]"; }
    }
    public record View(Long id,Long authorId,String authorRole,String body,Instant createdAt) {
        @Override public String toString() { return "CommentView[redacted]"; }
        static View from(Comment c) { return new View(c.getId(),c.getAuthor().getId(),c.getAuthor().getRole().getName(),c.getBody(),c.getCreatedAt()); }
    }
    private final CommentRepository repository;
    private final CommunicationAccess access;
    private final UserRepository users;
    private final NotificationPublisher notifications;
    public CommentService(CommentRepository repository,CommunicationAccess access,UserRepository users,NotificationPublisher notifications) {
        this.repository=repository;this.access=access;this.users=users;this.notifications=notifications;
    }
    @Transactional public View create(long id,Input input) {
        var request=access.require(id,true);
        if(Set.of(RequestStatus.CLOSED,RequestStatus.REJECTED).contains(request.getStatus())) throw new ConflictException("Terminal request conversations are read-only.");
        var comment=new Comment();comment.setRequest(request);comment.setAuthor(users.findById(access.actorId()).orElseThrow());comment.setBody(input.body());
        repository.saveAndFlush(comment);notifications.publish(request,"COMMENT","comment:"+comment.getId(),access.actorId());return View.from(comment);
    }
    public PageResponse<View> list(long id,int page,int size) {
        access.require(id,false);return PageResponse.from(repository.findByRequestId(id,PageRequest.of(page,size,Sort.by("createdAt","id"))).map(View::from));
    }
}
