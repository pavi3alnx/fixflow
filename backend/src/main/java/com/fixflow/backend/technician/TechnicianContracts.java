package com.fixflow.backend.technician;

import com.fixflow.backend.identity.User;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.*;
import com.fixflow.backend.common.api.BadRequestException;

public final class TechnicianContracts {
    private TechnicianContracts() { }
    public record Profile(Long id, String fullName, String email, String studentStaffId, String department,
                          boolean enabled, boolean emailVerified, boolean administrativelyDisabled,java.time.LocalDateTime updatedAt) {
        static Profile from(User user) {
            return new Profile(user.getId(), user.getFullName(), user.getEmail(), user.getStudentStaffId(),
                    user.getDepartment(), user.isEnabled(), user.isEmailVerified(), user.isAdministrativelyDisabled(),user.getUpdatedAt());
        }
    }
    public record Update(@NotBlank @Size(max=150) String fullName, @NotBlank @Size(max=100) String department,
                         @NotNull Boolean enabled,@NotNull Boolean administrativelyDisabled,@NotNull java.time.LocalDateTime expectedUpdatedAt) { }
    public record Query(@Min(0) @Max(100000) Integer page, @Min(1) @Max(100) Integer size,
                        @Size(max=100) String search, Boolean enabled, @Size(max=30) String sort) {
        public Query { page = page == null ? 0 : page; size = size == null ? 20 : size;
            search = search == null ? "" : search.strip(); sort = sort == null ? "fullName,asc" : sort; }
        public Pageable pageable() {
            String[] parts = sort.split(",", -1);
            if (parts.length != 2 || !Set.of("id","fullName","email","department").contains(parts[0])
                    || !Set.of("asc","desc").contains(parts[1])) throw new BadRequestException("Unsupported technician sort.");
            var order = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]);
            return PageRequest.of(page,size,parts[0].equals("id") ? order : order.and(Sort.by("id")));
        }
    }
    public record Workload(long totalActive, List<Bucket> breakdown) { }
    public record Bucket(String status, String priority, long count) { }
}
