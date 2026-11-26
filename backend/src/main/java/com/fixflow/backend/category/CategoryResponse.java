package com.fixflow.backend.category;

public record CategoryResponse(Long id, String code, String name, String description, boolean active) {
    static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getCode(), category.getName(),
                category.getDescription(), category.isActive());
    }
}
