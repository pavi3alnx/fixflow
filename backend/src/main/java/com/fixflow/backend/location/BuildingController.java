package com.fixflow.backend.location;

import com.fixflow.backend.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/buildings")
public class BuildingController {
    private final BuildingService service;
    public BuildingController(BuildingService service) { this.service = service; }
    @GetMapping public PageResponse<BuildingResponse> list(@ModelAttribute @Valid CatalogQuery query) {
        return service.list(query);
    }
    @GetMapping("/{id}") public BuildingResponse get(@PathVariable @Positive long id) { return service.get(id); }
    @PostMapping public ResponseEntity<BuildingResponse> create(@RequestBody @Valid BuildingRequest input) {
        var response = service.create(input);
        return ResponseEntity.created(URI.create("/api/buildings/" + response.id())).body(response);
    }
    @PutMapping("/{id}") public BuildingResponse update(@PathVariable @Positive long id,
                                                       @RequestBody @Valid BuildingRequest input) {
        return service.update(id, input);
    }
}
