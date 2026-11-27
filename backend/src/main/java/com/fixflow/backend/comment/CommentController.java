package com.fixflow.backend.comment;

import com.fixflow.backend.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/maintenance-requests/{id}/comments")
public class CommentController {
    private final CommentService service;
    public CommentController(CommentService service) { this.service=service; }
    @PostMapping public ResponseEntity<CommentService.View> create(@PathVariable @Positive long id,@RequestBody @Valid CommentService.Input input) {
        var value=service.create(id,input);return ResponseEntity.created(URI.create("/api/maintenance-requests/"+id+"/comments")).body(value);
    }
    @GetMapping public PageResponse<CommentService.View> list(@PathVariable @Positive long id,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.list(id,page,size); }
}
