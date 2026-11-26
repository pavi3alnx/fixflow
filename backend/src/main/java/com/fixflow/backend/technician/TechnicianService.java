package com.fixflow.backend.technician;

import com.fixflow.backend.assignment.AssignmentRepository;
import com.fixflow.backend.auth.*;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.technician.TechnicianContracts.*;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasRole('ADMIN')")
public class TechnicianService {
    private final UserRepository users;
    private final AssignmentRepository assignments;
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    public TechnicianService(UserRepository users, AssignmentRepository assignments, AuthService auth, JdbcTemplate jdbc) {
        this.users=users; this.assignments=assignments; this.auth=auth; this.jdbc=jdbc;
    }
    @Transactional public Profile create(AuthContracts.Registration input) {
        var result=auth.provisionTechnician(input); return get(result.id());
    }
    public Profile get(long id) { return Profile.from(technician(id)); }
    public PageResponse<Profile> list(Query query) {
        org.springframework.data.jpa.domain.Specification<User> spec=(root, criteria, cb)-> {
            var predicate=cb.equal(root.get("role").get("name"),"TECHNICIAN");
            if(query.enabled()!=null) predicate=cb.and(predicate,cb.equal(root.get("enabled"),query.enabled()));
            if(!query.search().isEmpty()) {
                String pattern="%"+query.search().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
                predicate=cb.and(predicate,cb.or(cb.like(cb.lower(root.get("fullName")),pattern,'\\'),cb.like(cb.lower(root.get("email")),pattern,'\\')));
            }
            return predicate;
        };
        return PageResponse.from(users.findAll(spec,query.pageable()).map(Profile::from));
    }
    @Transactional public Profile update(long id,Update input) {
        var user=users.lockById(id).orElseThrow(ResourceNotFoundException::new); requireRole(user);
        if(!user.getUpdatedAt().equals(input.expectedUpdatedAt())) throw new ConflictException("The technician profile has changed. Reload and retry.");
        if(!input.enabled() && assignments.existsByTechnicianIdAndEndedAtIsNull(id))
            throw new ConflictException("Reassign all active work before deactivating this technician.");
        if(input.enabled() && (!user.isEmailVerified() || input.administrativelyDisabled())) throw new ConflictException("A verified, non-suspended account is required for activation.");
        user.setFullName(input.fullName().strip()); user.setDepartment(input.department().strip());
        if(user.isEnabled() && !input.enabled() || !user.isAdministrativelyDisabled() && input.administrativelyDisabled()) user.setTokenVersion(user.getTokenVersion()+1);
        user.setAdministrativelyDisabled(input.administrativelyDisabled()); user.setEnabled(input.enabled()); users.flush(); return Profile.from(user);
    }
    public Workload workload(long id) {
        technician(id);
        var rows=jdbc.query("select r.status,r.priority,count(*) as total from assignments a join maintenance_requests r on r.id=a.request_id where a.technician_id=? and a.ended_at is null group by r.status,r.priority order by r.status,r.priority",
                (rs,n)->new Bucket(rs.getString("status"),rs.getString("priority"),rs.getLong("total")),id);
        return new Workload(rows.stream().mapToLong(Bucket::count).sum(),rows);
    }
    @PreAuthorize("hasRole('TECHNICIAN')") public Workload ownWorkload() {
        long id=Long.parseLong(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName());
        return workload(id);
    }
    private User technician(long id) { var user=users.findById(id).orElseThrow(ResourceNotFoundException::new); requireRole(user); return user; }
    private void requireRole(User user) { if(!user.getRole().getName().equals("TECHNICIAN")) throw new ResourceNotFoundException(); }
}
