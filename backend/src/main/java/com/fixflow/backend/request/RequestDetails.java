package com.fixflow.backend.request;

import jakarta.validation.constraints.*;

/** Editable reporter fields only; ownership, workflow and audit fields are server-managed. */
public record RequestDetails(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10000) String description,
        @NotNull @Positive Long buildingId,
        @Positive Long roomId,
        @NotNull @Positive Long categoryId,
        @NotNull Priority suggestedPriority) {
    public RequestDetails {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
    }
}
