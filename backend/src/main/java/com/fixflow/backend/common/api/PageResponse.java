package com.fixflow.backend.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable JSON pagination independent of Spring Data's internal Page implementation. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
