package com.fixflow.backend.category;

import com.fixflow.backend.common.api.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMIN', 'REPORTER', 'TECHNICIAN')")
public class CategoryService {
    private final CategoryRepository repository;
    public CategoryService(CategoryRepository repository) { this.repository = repository; }
    public CategoryResponse get(long id) {
        var category = repository.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (!category.isActive() && !CatalogSupport.isAdmin()) throw new ResourceNotFoundException();
        return CategoryResponse.from(category);
    }
    public PageResponse<CategoryResponse> list(CatalogQuery query) {
        return PageResponse.from(repository.findAll(CatalogSupport.filter(query), query.pageable()).map(CategoryResponse::from));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse create(CategoryRequest input) {
        var category = new Category(); category.setCode(CatalogSupport.code(input.code())); apply(category, input);
        return CategoryResponse.from(repository.saveAndFlush(category));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse update(long id, CategoryRequest input) {
        var category = repository.lockById(id).orElseThrow(ResourceNotFoundException::new);
        if (!category.getCode().equals(CatalogSupport.code(input.code())))
            throw new ConflictException("Category codes are stable identifiers and cannot be changed.");
        apply(category, input); repository.flush(); return CategoryResponse.from(category);
    }
    private void apply(Category category, CategoryRequest input) {
        category.setName(input.name().strip()); category.setDescription(CatalogSupport.optionalText(input.description()));
        category.setActive(input.active());
    }
}
