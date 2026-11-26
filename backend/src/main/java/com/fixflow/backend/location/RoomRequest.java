package com.fixflow.backend.location;

import jakarta.validation.constraints.*;
import com.fixflow.backend.common.api.CatalogSupport;

public record RoomRequest(@NotNull @Positive Long buildingId,
                          @NotBlank @Size(max = 50) String code,
                          @Size(max = 150) String name,
                          @Size(max = 5000) String description,
                          @NotNull Boolean active) {
    public RoomRequest {
        code = code == null ? null : CatalogSupport.code(code);
        name = CatalogSupport.optionalText(name);
        description = CatalogSupport.optionalText(description);
    }
}
