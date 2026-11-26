package com.fixflow.backend.assignment;

import com.fixflow.backend.assignment.AssignmentContracts.*;
import com.fixflow.backend.common.api.PageResponse;
import com.fixflow.backend.request.RequestResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AssignmentWorkflowController {
    private final AssignmentWorkflowService service;
    public AssignmentWorkflowController(AssignmentWorkflowService service) { this.service=service; }
    @PostMapping("/api/admin/maintenance-requests/{id}/assignments") public ResponseEntity<RequestResponse> assign(@PathVariable @Positive long id,@RequestBody @Valid Assign input) {
        return ResponseEntity.created(URI.create("/api/maintenance-requests/"+id+"/assignment")).body(service.assign(id,input,false));
    }
    @PatchMapping("/api/admin/maintenance-requests/{id}/assignment") public RequestResponse reassign(@PathVariable @Positive long id,@RequestBody @Valid Assign input) { return service.assign(id,input,true); }
    @GetMapping("/api/maintenance-requests/{id}/assignment") public View current(@PathVariable @Positive long id) { return service.current(id); }
    @GetMapping("/api/admin/maintenance-requests/{id}/assignments") public PageResponse<AdminView> history(@PathVariable @Positive long id,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.history(id,page,size); }
    @PatchMapping("/api/technician/maintenance-requests/{id}/accept") public RequestResponse accept(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.accept(id,input); }
    @PatchMapping("/api/technician/maintenance-requests/{id}/start") public RequestResponse start(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.work(id,"start",input); }
    @PatchMapping("/api/technician/maintenance-requests/{id}/hold") public RequestResponse hold(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.work(id,"hold",input); }
    @PatchMapping("/api/technician/maintenance-requests/{id}/resume") public RequestResponse resume(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.work(id,"resume",input); }
    @PatchMapping("/api/technician/maintenance-requests/{id}/progress") public RequestResponse progress(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.progress(id,input); }
    @PatchMapping("/api/technician/maintenance-requests/{id}/resolve") public RequestResponse resolve(@PathVariable @Positive long id,@RequestBody @Valid Resolution input) { return service.resolve(id,input); }
    @PatchMapping("/api/maintenance-requests/{id}/confirmation") public RequestResponse confirm(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.reporterDecision(id,input,false); }
    @PatchMapping("/api/maintenance-requests/{id}/reopening") public RequestResponse reopen(@PathVariable @Positive long id,@RequestBody @Valid Action input) { return service.reporterDecision(id,input,true); }
}
