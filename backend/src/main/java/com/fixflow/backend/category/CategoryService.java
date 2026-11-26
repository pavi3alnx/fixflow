package com.fixflow.backend.category;

import com.fixflow.backend.common.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryRepository repository;
    public CategoryService(CategoryRepository repository) { this.repository = repository; }
    public CategoryResponse get(long id) {
        return CategoryResponse.from(repository.findById(id).orElseThrow(ResourceNotFoundException::new));
    }
}
