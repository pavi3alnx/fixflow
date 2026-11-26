package com.fixflow.backend.request;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record RequestQuery(@Min(0) @Max(100000) Integer page,
                           @Min(1) @Max(100) Integer size,
                           @Size(max = 100) String search,
                           RequestStatus status,
                           @Positive Long buildingId,
                           @Positive Long categoryId,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant submittedFrom,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant submittedTo,
                           @Size(max = 30) String sort,
                           Priority priority) {
    public RequestQuery(Integer page, Integer size, String search, RequestStatus status, Long buildingId,
                        Long categoryId, Instant submittedFrom, Instant submittedTo, String sort) {
        this(page, size, search, status, buildingId, categoryId, submittedFrom, submittedTo, sort, null);
    }
    public RequestQuery {
        page = page == null ? 0 : page; size = size == null ? 20 : size;
        search = search == null ? "" : search.strip(); sort = sort == null ? "createdAt,desc" : sort;
    }
    @AssertTrue(message = "submittedFrom must be before submittedTo")
    public boolean isDateRangeValid() {
        return submittedFrom == null || submittedTo == null || submittedFrom.isBefore(submittedTo);
    }
    public Pageable pageable() {
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !Set.of("id", "title", "createdAt", "updatedAt", "status", "priority", "suggestedPriority").contains(parts[0])
                || !(parts[1].equals("asc") || parts[1].equals("desc")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported request sort");
        var direction = Sort.Direction.fromString(parts[1]);
        var order = Sort.by(direction, parts[0]);
        if (!parts[0].equals("id")) order = order.and(Sort.by(direction, "id"));
        return PageRequest.of(page, size, order);
    }
    public Specification<MaintenanceRequest> filter() {
        return (root, query, builder) -> {
            var result = builder.conjunction();
            if (status != null) result = builder.and(result, builder.equal(root.get("status"), status));
            if (priority != null) result = builder.and(result, builder.equal(root.get("priority"), priority));
            if (buildingId != null) result = builder.and(result, builder.equal(root.get("building").get("id"), buildingId));
            if (categoryId != null) result = builder.and(result, builder.equal(root.get("category").get("id"), categoryId));
            if (submittedFrom != null) result = builder.and(result, builder.greaterThanOrEqualTo(root.get("createdAt"), submittedFrom));
            if (submittedTo != null) result = builder.and(result, builder.lessThan(root.get("createdAt"), submittedTo));
            if (!search.isEmpty()) {
                String pattern = "%" + search.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                result = builder.and(result, builder.or(builder.like(builder.lower(root.get("title")), pattern, '\\'),
                        builder.like(builder.lower(root.get("description")), pattern, '\\')));
            }
            return result;
        };
    }
}
