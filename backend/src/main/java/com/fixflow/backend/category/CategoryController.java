package com.fixflow.backend.category;

import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService service;
    public CategoryController(CategoryService service) { this.service = service; }
    @GetMapping("/{id}")
    public CategoryResponse get(@PathVariable @Positive long id) { return service.get(id); }
}
