package com.fixflow.backend.common.api;

import jakarta.validation.constraints.*;
import java.util.Set;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared, bounded query contract for the three reference-data collections. */
public record CatalogQuery(@Min(0) @Max(100000) Integer page,
                           @Min(1) @Max(100) Integer size,
                           @Size(max = 100) String search,
                           Boolean active,
                           @Size(max = 30) String sort) {
    public CatalogQuery {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        search = search == null ? "" : search.strip();
        sort = sort == null ? "code,asc" : sort;
    }
    public Pageable pageable() {
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !Set.of("id", "code", "name", "active").contains(parts[0])
                || !(parts[1].equals("asc") || parts[1].equals("desc")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort");
        var order = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]);
        if (!parts[0].equals("id")) order = order.and(Sort.by("id"));
        return PageRequest.of(page, size, order);
    }
}
