package com.fixflow.backend.assignment;

import com.fixflow.backend.assignment.AssignmentContracts.*;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.request.*;
import com.fixflow.backend.security.RequestAccess;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','REPORTER')")
public class AssignmentWorkflowService {
    private final AssignmentRepository assignments;
    private final UserRepository users;
    private final RequestWorkflowWriter writer;
    private final RequestAccess access;
    private final StatusHistoryRepository history;
    private final AssignmentEvidenceRepository evidence;
    private final com.fixflow.backend.notification.NotificationPublisher notifications;
    public AssignmentWorkflowService(AssignmentRepository assignments,UserRepository users,RequestWorkflowWriter writer,
                                     RequestAccess access,StatusHistoryRepository history,AssignmentEvidenceRepository evidence,com.fixflow.backend.notification.NotificationPublisher notifications) {
        this.assignments=assignments;this.users=users;this.writer=writer;this.access=access;this.history=history;this.evidence=evidence;
        this.notifications=notifications;
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public RequestResponse assign(long id,Assign input,boolean reassign) {
        var request=writer.locked(id,input.expectedUpdatedAt()); RequestWorkflowPolicy.validateAssignment(request.getStatus(),reassign);
        var technician=eligible(input.technicianId());
        var old=assignments.findByRequestIdAndEndedAtIsNull(id).orElse(null);
        if(reassign) {
            requireNote(input.reason(),2000);
            if(old==null) throw new ConflictException("There is no current assignment to replace.");
            if(old.getTechnician().getId().equals(technician.getId())) throw new ConflictException("Select a different technician for reassignment.");
        } else if(old!=null) throw new ConflictException("The request already has a current assignment.");
        Instant now=eventTime(request);
        if(old!=null) { old.setEndedAt(now);old.setEndReason(AssignmentEndReason.REASSIGNED);old.setReassignmentReason(input.reason());assignments.flush(); }
        var assignment=new Assignment();assignment.setRequest(request);assignment.setTechnician(technician);
        assignment.setAssignedBy(users.getReferenceById(writer.actorId()));assignment.setAssignedAt(now);assignments.saveAndFlush(assignment);
        if(request.getStatus()==RequestStatus.ASSIGNED) writer.touch(request);
        else writer.transition(request,RequestStatus.ASSIGNED,input.reason());
        notifications.publish(request,reassign?"REASSIGNED":"ASSIGNED","assignment:"+assignment.getId(),writer.actorId());
        return RequestResponse.from(request);
    }
    @Transactional @PreAuthorize("hasRole('TECHNICIAN')")
    public RequestResponse accept(long id,Action input) {
        var request=writer.locked(id);var assignment=owned(id);writer.checkExpected(request,input.expectedUpdatedAt());
        if(request.getStatus()!=RequestStatus.ASSIGNED || assignment.getAcceptedAt()!=null) throw new ConflictException("Only an unaccepted assigned request can be accepted.");
        assignment.setAcceptedAt(eventTime(request));writer.touch(request);assignments.flush();
        notifications.publish(request,"WORK_ACCEPTED","accept:"+assignment.getId(),writer.actorId());return RequestResponse.from(request);
    }
    @Transactional @PreAuthorize("hasRole('TECHNICIAN')")
    public RequestResponse work(long id,String operation,Action input) {
        var request=writer.locked(id);var assignment=owned(id);writer.checkExpected(request,input.expectedUpdatedAt());
        RequestStatus target=switch(operation) {
            case "start","resume" -> RequestStatus.IN_PROGRESS;
            case "hold" -> RequestStatus.ON_HOLD;
            default -> throw new BadRequestException("Unknown work operation.");
        };
        if(operation.equals("start") && request.getStatus()!=RequestStatus.ASSIGNED
                || operation.equals("resume") && request.getStatus()!=RequestStatus.ON_HOLD)
            throw new ConflictException("This work operation is not valid in the current state.");
        RequestWorkflowPolicy.validateWork(request.getStatus(),target);
        if(assignment.getAcceptedAt()==null) throw new ConflictException("Accept the assignment before starting work.");
        if(!operation.equals("start") && assignment.getStartedAt()==null) throw new ConflictException("Work has not started.");
        if(operation.equals("hold")) requireNote(input.notes(),2000);
        var now=eventTime(request);
        if(operation.equals("start")) assignment.setStartedAt(now);
        if(input.notes()!=null) append(assignment,input.notes(),now);
        writer.transition(request,target,input.notes());assignments.flush();return RequestResponse.from(request);
    }
    @Transactional @PreAuthorize("hasRole('TECHNICIAN')")
    public RequestResponse progress(long id,Action input) {
        var request=writer.locked(id);var assignment=owned(id);writer.checkExpected(request,input.expectedUpdatedAt());
        if(!Set.of(RequestStatus.IN_PROGRESS,RequestStatus.ON_HOLD).contains(request.getStatus()) || assignment.getStartedAt()==null)
            throw new ConflictException("Progress can be recorded only after work starts.");
        requireNote(input.notes(),10000);append(assignment,input.notes(),eventTime(request));writer.touch(request);assignments.flush();
        notifications.publish(request,"WORK_PROGRESS","progress:"+id+":"+request.getUpdatedAt(),writer.actorId());return RequestResponse.from(request);
    }
    @Transactional @PreAuthorize("hasRole('TECHNICIAN')")
    public RequestResponse resolve(long id,Resolution input) {
        var request=writer.locked(id);var assignment=owned(id);writer.checkExpected(request,input.expectedUpdatedAt());
        RequestWorkflowPolicy.validateWork(request.getStatus(),RequestStatus.RESOLVED);
        if(assignment.getStartedAt()==null || assignment.getAcceptedAt()==null) throw new ConflictException("Work must be accepted and started before resolution.");
        requireNote(input.repairNotes(),10000);
        if(new HashSet<>(input.evidenceAttachmentIds()).size()!=input.evidenceAttachmentIds().size()) throw new BadRequestException("Evidence references must be unique.");
        evidence.attach(assignment.getId(),id,writer.actorId(),input.evidenceAttachmentIds());
        Instant now=eventTime(request);append(assignment,input.repairNotes(),now);
        assignment.setResolvedAt(now);assignment.setEndedAt(now);assignment.setEndReason(AssignmentEndReason.RESOLVED);
        writer.transition(request,RequestStatus.RESOLVED,null);assignments.flush();return RequestResponse.from(request);
    }
    @Transactional @PreAuthorize("hasRole('REPORTER')")
    public RequestResponse reporterDecision(long id,Action input,boolean reopen) {
        var request=writer.locked(id);
        if(!request.getReporter().getId().equals(writer.actorId())) throw new ResourceNotFoundException();
        writer.checkExpected(request,input.expectedUpdatedAt());
        var target=reopen?RequestStatus.REOPENED:RequestStatus.CONFIRMED;RequestWorkflowPolicy.validateReporter(request.getStatus(),target);
        if(input.notes()!=null && input.notes().length()>2000) throw new BadRequestException("Reporter notes must not exceed 2000 characters.");
        var latest=assignments.findFirstByRequestIdOrderByAssignedAtDescIdDesc(id).orElseThrow(()->new ConflictException("Current resolution evidence is missing."));
        var lastEvent=history.findFirstByRequestIdOrderByChangedAtDescIdDesc(id).orElseThrow(()->new ConflictException("Resolution history is missing."));
        if(!assignments.hasLatestResolution(id) || lastEvent.getNewStatus()!=RequestStatus.RESOLVED
                || lastEvent.getChangedAt().isBefore(latest.getResolvedAt()) || assignments.existsByRequestIdAndEndedAtIsNull(id))
            throw new ConflictException("Completed evidence for this resolution cycle is required.");
        if(reopen) requireNote(input.notes(),2000);
        writer.transition(request,target,input.notes());return RequestResponse.from(request);
    }
    @PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
    public View current(long id) {
        if(!access.canRead(SecurityContextHolder.getContext().getAuthentication(),id)) throw new ResourceNotFoundException();
        var assignment=assignments.findByRequestIdAndEndedAtIsNull(id).orElseThrow(ResourceNotFoundException::new);
        return View.from(assignment,evidence.find(assignment.getId()));
    }
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AdminView> history(long id,int page,int size) {
        if(!access.canRead(SecurityContextHolder.getContext().getAuthentication(),id)) throw new ResourceNotFoundException();
        var rows=assignments.findByRequestId(id,PageRequest.of(page,size,Sort.by("assignedAt","id")));
        var links=evidence.findAll(rows.getContent().stream().map(Assignment::getId).toList());
        return PageResponse.from(rows.map(a->new AdminView(View.from(a,links.getOrDefault(a.getId(),List.of())),a.getAssignedBy().getId(),a.getReassignmentReason())));
    }
    private Assignment owned(long id) {
        var assignment=assignments.findByRequestIdAndEndedAtIsNull(id).orElseThrow(()->{
            var last=assignments.findFirstByRequestIdOrderByAssignedAtDescIdDesc(id).orElse(null);
            if(last!=null && last.getTechnician().getId().equals(writer.actorId())) return new ConflictException("This assignment has already ended.");
            return new ResourceNotFoundException();
        });
        if(!assignment.getTechnician().getId().equals(writer.actorId())) throw new ResourceNotFoundException();
        eligible(writer.actorId());return assignment;
    }
    private User eligible(long id) {
        var user=users.lockById(id).orElseThrow(ResourceNotFoundException::new);
        if(!user.getRole().getName().equals("TECHNICIAN") || !user.isEnabled() || !user.isEmailVerified() || user.isAdministrativelyDisabled())
            throw new ConflictException("An active verified technician is required.");
        return user;
    }
    private Instant eventTime(MaintenanceRequest request) {
        var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var time=now.isAfter(request.getUpdatedAt())?now:request.getUpdatedAt().plus(1,ChronoUnit.MICROS);
        request.setUpdatedAt(time);return time;
    }
    private void requireNote(String note,int max) {
        if(note==null || note.strip().length()<5 || note.length()>max) throw new BadRequestException("A meaningful note of 5 to "+max+" characters is required.");
    }
    private void append(Assignment assignment,String note,Instant timestamp) {
        String text=(assignment.getRepairNotes()==null?"":assignment.getRepairNotes()+"\n")+"["+timestamp+"] "+note;
        if(text.length()>20000) throw new BadRequestException("Assignment notes exceed the 20000-character limit.");
        assignment.setRepairNotes(text);
    }
}
