package com.fixflow.backend.request;

import jakarta.validation.constraints.*;

/** Representative input contract; request submission is outside FF-03. */
public record RequestDetails(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,
        @NotNull @Positive Long buildingId,
        @Positive Long roomId,
        @NotNull @Positive Long categoryId,
        @NotNull Priority suggestedPriority) { }
