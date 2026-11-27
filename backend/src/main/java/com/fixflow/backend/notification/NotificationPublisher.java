package com.fixflow.backend.notification;

import com.fixflow.backend.request.MaintenanceRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Synchronous database writes in the business transaction: no after-commit event-loss window. */
@Component
public class NotificationPublisher {
    private final JdbcTemplate jdbc;
    public NotificationPublisher(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(MaintenanceRequest request,String type,String eventKey,long actor) {
        jdbc.update("""
            insert into notifications(recipient_id,request_id,event_type,title,message,event_key)
            select u.id,?,?, 'Maintenance activity','There is an update to your maintenance activity.',?
            from users u join roles ro on ro.id=u.role_id
            where u.enabled and u.email_verified and not u.administratively_disabled and u.id<>?
              and (ro.name='ADMIN' or u.id=? or exists
                (select 1 from assignments a where a.request_id=? and a.technician_id=u.id and a.ended_at is null))
            on conflict(recipient_id,event_key) where event_key is not null do nothing
            """,request.getId(),type,eventKey,actor,request.getReporter().getId(),request.getId());
    }
}
