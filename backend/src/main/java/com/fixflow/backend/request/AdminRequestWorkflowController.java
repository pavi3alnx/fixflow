package com.fixflow.backend.request;

import com.fixflow.backend.common.api.PageResponse;
import com.fixflow.backend.request.AdminWorkflowContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/maintenance-requests/{id}")
public class AdminRequestWorkflowController {
    private final AdminRequestWorkflowService service;
    public AdminRequestWorkflowController(AdminRequestWorkflowService service) { this.service = service; }
    @PatchMapping("/verification") public RequestResponse verify(@PathVariable @Positive long id, @RequestBody @Valid Transition input) {
        return service.verify(id, input);
    }
    @PatchMapping("/priority") public RequestResponse priority(@PathVariable @Positive long id, @RequestBody @Valid PriorityChange input) {
        return service.priority(id, input);
    }
    @PatchMapping("/status") public RequestResponse status(@PathVariable @Positive long id, @RequestBody @Valid Transition input) {
        return service.status(id, input);
    }
    @GetMapping("/history") public PageResponse<AuditEntry> history(@PathVariable @Positive long id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(id, page, size);
    }
}
