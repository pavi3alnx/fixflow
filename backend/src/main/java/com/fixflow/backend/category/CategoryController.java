package com.fixflow.backend.category;

import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import com.fixflow.backend.common.api.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService service;
    public CategoryController(CategoryService service) { this.service = service; }
    @GetMapping("/{id}")
    public CategoryResponse get(@PathVariable @Positive long id) { return service.get(id); }
    @GetMapping public PageResponse<CategoryResponse> list(@ModelAttribute @Valid CatalogQuery query) {
        return service.list(query);
    }
    @PostMapping public ResponseEntity<CategoryResponse> create(@RequestBody @Valid CategoryRequest input) {
        var response = service.create(input);
        return ResponseEntity.created(URI.create("/api/categories/" + response.id())).body(response);
    }
    @PutMapping("/{id}") public CategoryResponse update(@PathVariable @Positive long id,
                                                       @RequestBody @Valid CategoryRequest input) {
        return service.update(id, input);
    }
}
