package com.fixflow.backend.common.api;

import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CatalogSupport {
    private CatalogSupport() { }
    public static boolean isAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    public static Boolean activeFilter(Boolean requested) {
        if (isAdmin()) return requested;
        if (Boolean.FALSE.equals(requested)) throw new AccessDeniedException("Inactive catalog access denied");
        return true;
    }
    public static String code(String code) { return code.strip().toUpperCase(Locale.ROOT); }
    public static String optionalText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
    public static <T> Specification<T> filter(CatalogQuery query) {
        Boolean active = activeFilter(query.active());
        return (root, criteria, builder) -> {
            var result = builder.conjunction();
            if (active != null) result = builder.and(result, builder.equal(root.get("active"), active));
            if (!query.search().isEmpty()) {
                // Treat '%' and '_' as literal user text, never wildcard controls.
                String text = "%" + query.search().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                result = builder.and(result, builder.or(builder.like(builder.lower(root.get("code")), text, '\\'),
                        builder.like(builder.lower(root.get("name")), text, '\\')));
            }
            return result;
        };
    }
}
