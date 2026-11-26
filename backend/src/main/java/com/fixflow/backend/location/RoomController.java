package com.fixflow.backend.location;

import com.fixflow.backend.common.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final RoomService service;
    public RoomController(RoomService service) { this.service = service; }
    @GetMapping public PageResponse<RoomResponse> list(@ModelAttribute @Valid CatalogQuery query,
                                                     @RequestParam(required = false) @Positive Long buildingId) {
        return service.list(query, buildingId);
    }
    @GetMapping("/{id}") public RoomResponse get(@PathVariable @Positive long id) { return service.get(id); }
    @PostMapping public ResponseEntity<RoomResponse> create(@RequestBody @Valid RoomRequest input) {
        var response = service.create(input);
        return ResponseEntity.created(URI.create("/api/rooms/" + response.id())).body(response);
    }
    @PutMapping("/{id}") public RoomResponse update(@PathVariable @Positive long id,
                                                   @RequestBody @Valid RoomRequest input) {
        return service.update(id, input);
    }
}
