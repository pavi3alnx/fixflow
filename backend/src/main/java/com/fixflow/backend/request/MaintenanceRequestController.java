package com.fixflow.backend.request;

import com.fixflow.backend.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/maintenance-requests")
public class MaintenanceRequestController {
    private final MaintenanceRequestService service;
    public MaintenanceRequestController(MaintenanceRequestService service) { this.service = service; }
    @PostMapping public ResponseEntity<RequestResponse> create(@RequestBody @Valid RequestDetails input) {
        var response = service.create(input);
        return ResponseEntity.created(URI.create("/api/maintenance-requests/" + response.id())).body(response);
    }
    @GetMapping public PageResponse<RequestResponse> list(@ModelAttribute @Valid RequestQuery query) { return service.list(query); }
    @GetMapping("/{id}") public RequestResponse get(@PathVariable @Positive long id) { return service.get(id); }
    @GetMapping("/{id}/history") public PageResponse<StatusHistoryResponse> history(@PathVariable @Positive long id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(id, page, size);
    }
    @PutMapping("/{id}") public RequestResponse update(@PathVariable @Positive long id,
                                                       @RequestBody @Valid RequestDetails input) {
        return service.update(id, input);
    }
}
