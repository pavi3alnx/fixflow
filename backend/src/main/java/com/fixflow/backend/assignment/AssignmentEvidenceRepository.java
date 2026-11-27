package com.fixflow.backend.assignment;

import java.util.List;
import com.fixflow.backend.common.api.BadRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Uses the same datasource/transaction as JPA; no duplicate attachment domain model. */
@Repository
public class AssignmentEvidenceRepository {
    private final JdbcTemplate jdbc;
    public AssignmentEvidenceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void attach(long assignmentId,long requestId,long technicianId,List<Long> ids) {
        for(Long id:ids) {
            var valid=jdbc.queryForObject("select count(*) from attachments t where id=? and request_id=? and uploaded_by=? and purpose='REPAIR_EVIDENCE' and created_at >= (select assigned_at from assignments where id=?) and (assignment_id is null or assignment_id=?) and not exists(select 1 from assignment_evidence e where e.attachment_id=t.id)",Long.class,id,requestId,technicianId,assignmentId,assignmentId);
            if(valid==null || valid!=1) throw new BadRequestException("Evidence must be this technician's repair-evidence attachment for the request.");
            jdbc.update("insert into assignment_evidence(assignment_id,attachment_id) values (?,?)",assignmentId,id);
        }
    }
    public List<Long> find(long assignmentId) {
        return jdbc.query("select attachment_id from assignment_evidence where assignment_id=? order by attachment_id",(rs,n)->rs.getLong(1),assignmentId);
    }
    public java.util.Map<Long,List<Long>> findAll(java.util.List<Long> ids) {
        var result=new java.util.HashMap<Long,List<Long>>();
        if(ids.isEmpty()) return result;
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        jdbc.query("select assignment_id,attachment_id from assignment_evidence where assignment_id in ("+placeholders+") order by assignment_id,attachment_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.computeIfAbsent(rs.getLong(1),ignored->new java.util.ArrayList<>()).add(rs.getLong(2)),ids.toArray());
        return result;
    }
}
