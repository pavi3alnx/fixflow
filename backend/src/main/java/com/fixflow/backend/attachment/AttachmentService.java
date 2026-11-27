package com.fixflow.backend.attachment;

import com.fixflow.backend.assignment.AssignmentRepository;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.UserRepository;
import com.fixflow.backend.request.RequestStatus;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
@PreAuthorize("hasAnyRole('REPORTER','TECHNICIAN','ADMIN')")
@Transactional(readOnly=true)
public class AttachmentService {
    public record View(Long id,Long requestId,Long uploadedBy,AttachmentPurpose purpose,Long assignmentId,String filename,String mediaType,long sizeBytes,Instant createdAt) {
        static View from(Attachment a) {return new View(a.getId(),a.getRequest().getId(),a.getUploadedBy().getId(),a.getPurpose(),a.getAssignment()==null?null:a.getAssignment().getId(),a.getOriginalFilename(),a.getMediaType(),a.getSizeBytes(),a.getCreatedAt());}
    }
    public record Download(byte[] bytes,String mediaType,String filename) { @Override public String toString(){return "Download[redacted]";} }
    private final AttachmentRepository repository;
    private final CommunicationAccess access;
    private final UserRepository users;
    private final AssignmentRepository assignments;
    private final PhotoValidator validator;
    private final PhotoStorage storage;
    private final PhotoSettings settings;
    public AttachmentService(AttachmentRepository repository,CommunicationAccess access,UserRepository users,AssignmentRepository assignments,PhotoValidator validator,PhotoStorage storage,PhotoSettings settings) {
        this.repository=repository;this.access=access;this.users=users;this.assignments=assignments;this.validator=validator;this.storage=storage;this.settings=settings;
    }
    @Transactional @PreAuthorize("hasAnyRole('REPORTER','TECHNICIAN')")
    public View upload(long id,AttachmentPurpose purpose,MultipartFile file) {
        var request=access.require(id,true);var attachment=new Attachment();attachment.setRequest(request);
        if(purpose==AttachmentPurpose.ISSUE_PHOTO) {
            if(!access.hasRole("REPORTER")) throw new AccessDeniedException("Reporter photo required");
            if(request.getStatus()!=RequestStatus.OPEN) throw new ConflictException("Issue photos may be added only to OPEN requests.");
        } else {
            if(!access.hasRole("TECHNICIAN")) throw new AccessDeniedException("Technician evidence required");
            if(!Set.of(RequestStatus.ASSIGNED,RequestStatus.IN_PROGRESS,RequestStatus.ON_HOLD).contains(request.getStatus())) throw new ConflictException("Repair evidence requires current active work.");
            var assignment=assignments.findByRequestIdAndEndedAtIsNull(id).orElseThrow(ResourceNotFoundException::new);
            if(!assignment.getTechnician().getId().equals(access.actorId())) throw new ResourceNotFoundException();
            attachment.setAssignment(assignment);
        }
        if(repository.countByRequestId(id)>=settings.getMaxFiles()) throw new ConflictException("The request photo-count limit has been reached.");
        var image=validator.validate(file);String key=UUID.randomUUID().toString().replace("-","")+"."+image.format();
        storage.put(key,image.bytes(),image.mediaType());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_ROLLED_BACK) try {storage.delete(key);}catch(RuntimeException e){System.getLogger(AttachmentService.class.getName()).log(System.Logger.Level.WARNING,"Photo rollback cleanup failed; orphan reconciliation is required.");}
                else if(status==STATUS_UNKNOWN) System.getLogger(AttachmentService.class.getName()).log(System.Logger.Level.WARNING,"Photo commit outcome is unknown; reconciliation is required.");
            }
        });
        attachment.setUploadedBy(users.getReferenceById(access.actorId()));attachment.setPurpose(purpose);attachment.setStorageProvider(storage.provider());attachment.setStorageKey(key);
        attachment.setOriginalFilename(image.filename());attachment.setMediaType(image.mediaType());attachment.setSizeBytes((long)image.bytes().length);
        repository.saveAndFlush(attachment);return View.from(attachment);
    }
    public PageResponse<View> list(long id,int page,int size) {access.require(id,false);return PageResponse.from(repository.findByRequestId(id,PageRequest.of(page,size,Sort.by("createdAt","id"))).map(View::from));}
    public Download download(long id,long attachmentId) {
        access.require(id,false);var attachment=repository.findByIdAndRequestId(attachmentId,id).orElseThrow(ResourceNotFoundException::new);
        if(!attachment.getStorageProvider().equals(storage.provider())) throw PhotoFailure.storage();
        String format=attachment.getMediaType().equals("image/png")?"png":attachment.getMediaType().equals("image/jpeg")?"jpeg":null;
        if(format==null) throw PhotoFailure.storage();
        return new Download(storage.get(attachment.getStorageKey()),attachment.getMediaType(),"photo-"+attachment.getId()+"."+format);
    }
}
