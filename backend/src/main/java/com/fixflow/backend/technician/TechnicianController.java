package com.fixflow.backend.technician;

import com.fixflow.backend.auth.AuthContracts;
import com.fixflow.backend.common.api.PageResponse;
import com.fixflow.backend.technician.TechnicianContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TechnicianController {
    private final TechnicianService service;
    public TechnicianController(TechnicianService service) { this.service=service; }
    @PostMapping("/api/admin/technicians") public ResponseEntity<Profile> create(@RequestBody @Valid AuthContracts.Registration input) {
        var result=service.create(input); return ResponseEntity.created(URI.create("/api/admin/technicians/"+result.id())).body(result);
    }
    @GetMapping("/api/admin/technicians") public PageResponse<Profile> list(@ModelAttribute @Valid Query query) { return service.list(query); }
    @GetMapping("/api/admin/technicians/{id}") public Profile get(@PathVariable @Positive long id) { return service.get(id); }
    @PutMapping("/api/admin/technicians/{id}") public Profile update(@PathVariable @Positive long id,@RequestBody @Valid Update input) { return service.update(id,input); }
    @GetMapping("/api/admin/technicians/{id}/workload") public Workload workload(@PathVariable @Positive long id) { return service.workload(id); }
    @GetMapping("/api/technician/workload") public Workload ownWorkload() { return service.ownWorkload(); }
}
