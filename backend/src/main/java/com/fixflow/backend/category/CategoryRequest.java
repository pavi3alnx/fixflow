package com.fixflow.backend.category;

import com.fixflow.backend.common.api.CatalogSupport;
import jakarta.validation.constraints.*;

public record CategoryRequest(@NotBlank @Size(max = 50) String code,
                              @NotBlank @Size(max = 100) String name,
                              @Size(max = 5000) String description,
                              @NotNull Boolean active) {
    public CategoryRequest {
        code = code == null ? null : CatalogSupport.code(code);
        name = name == null ? null : name.strip();
        description = CatalogSupport.optionalText(description);
    }
}
