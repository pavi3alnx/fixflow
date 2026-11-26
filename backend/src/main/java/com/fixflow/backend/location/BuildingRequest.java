package com.fixflow.backend.location;

import jakarta.validation.constraints.*;
import com.fixflow.backend.common.api.CatalogSupport;

public record BuildingRequest(@NotBlank @Size(max = 50) String code,
                              @NotBlank @Size(max = 150) String name,
                              @Size(max = 5000) String description,
                              @NotNull Boolean active) {
    public BuildingRequest {
        code = code == null ? null : CatalogSupport.code(code);
        name = name == null ? null : name.strip();
        description = CatalogSupport.optionalText(description);
    }
}
