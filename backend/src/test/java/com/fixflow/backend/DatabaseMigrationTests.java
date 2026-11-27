package com.fixflow.backend;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTests {
    @Test
    void communicationUpgradeFromV8PreservesRowsAndAddsScopedReferencesAndDeduplication() throws SQLException {
        flyway("8").migrate();fixture();
        execute("""
            INSERT INTO comments(request_id,author_id,body) VALUES(1,1,'Existing conversation');
            INSERT INTO assignments(request_id,technician_id,assigned_by) VALUES(1,2,3);
            INSERT INTO attachments(request_id,uploaded_by,purpose,storage_provider,storage_key,original_filename,media_type,size_bytes)
              VALUES(1,2,'REPAIR_EVIDENCE','legacy','old-key','photo.png','image/png',100);
            INSERT INTO assignment_evidence(assignment_id,attachment_id) VALUES(1,1);
            INSERT INTO notifications(recipient_id,request_id,event_type,title,message) VALUES(1,1,'LEGACY','Existing','Legacy message');
            """);
        var comments=rows("select id,request_id,author_id,body,created_at from comments");
        var photos=rows("select id,request_id,uploaded_by,storage_key,created_at from attachments");
        var notices=rows("select id,recipient_id,request_id,event_type,title,message,created_at,read_at from notifications");
        assertEquals(1,flyway(null).migrate().migrationsExecuted);
        assertEquals(comments,rows("select id,request_id,author_id,body,created_at from comments"));
        assertEquals(photos,rows("select id,request_id,uploaded_by,storage_key,created_at from attachments"));
        assertEquals(notices,rows("select id,recipient_id,request_id,event_type,title,message,created_at,read_at from notifications"));
        assertEquals(List.of("1|1|"),rows("select assignment_id,attachment_id from assignment_evidence"));
        assertEquals(List.of("true|"),rows("select (assignment_id is null)::text from attachments"));
        execute("UPDATE attachments SET assignment_id=1 WHERE id=1");
        rejected("23503","UPDATE attachments SET assignment_id=9223372036854775807 WHERE id=1");
        execute("UPDATE notifications SET event_key='legacy-event' WHERE id=1");
        rejected("23505","INSERT INTO notifications(recipient_id,request_id,event_type,title,message,event_key) VALUES(1,1,'LEGACY','Duplicate','Duplicate','legacy-event')");
        flyway(null).validate();
    }
    private TestDatabase database;
    @BeforeEach
    void isolatedDatabase() throws SQLException { database = new TestDatabase(); }

    private Flyway flyway(String target) {
        var c = Flyway.configure().dataSource(database.url, database.username, database.password)
                .defaultSchema("public").schemas("public").createSchemas(false)
                .baselineOnMigrate(false).baselineVersion("1").cleanDisabled(true);
        if (target != null) c.target(target);
        return c.load();
    }
    private void execute(String sql) throws SQLException {
        try (var c = database.connect(); var s = c.createStatement()) { s.execute(sql); }
    }
    private List<String> rows(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var c = database.connect(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            while (r.next()) {
                var row = new StringBuilder();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.append(r.getString(i)).append('|');
                rows.add(row.toString());
            }
        }
        return rows;
    }
    private void rejected(String state, String sql) {
        var e = assertThrows(SQLException.class, () -> execute(sql));
        assertEquals(state, e.getSQLState(), e.getMessage());
    }
    private void rejectedRestriction(String sql) {
        var e = assertThrows(SQLException.class, () -> execute(sql));
        // PostgreSQL 18 reports restrict_violation; 17 reports foreign_key_violation.
        assertTrue(List.of("23001", "23503").contains(e.getSQLState()), e.getMessage());
    }
    private void fixture() throws SQLException {
        execute("""
            INSERT INTO users(full_name,email,student_staff_id,department,password_hash,role_id)
            VALUES ('Reporter','r@example.invalid','R1','Test','synthetic-hash',1),
                   ('Technician','t@example.invalid','T1','Test','synthetic-hash',2),
                   ('Admin','a@example.invalid','A1','Test','synthetic-hash',3);
            INSERT INTO buildings(code,name) VALUES ('B1','One'),('B2','Two');
            INSERT INTO rooms(building_id,code) VALUES (1,'101'),(2,'202');
            INSERT INTO maintenance_requests(reporter_id,building_id,room_id,category_id,title,
                description,suggested_priority,priority)
            VALUES (1,1,NULL,1,'Building leak','Lobby leak','LOW','HIGH'),
                   (1,1,1,1,'Room leak','Pipe leak','MEDIUM','MEDIUM');
            """);
    }
    @Test
    void freshMigrationSeedsIdentityAndIsRepeatable() throws SQLException {
        var f = flyway(null);
        assertEquals(9, f.migrate().migrationsExecuted);
        assertEquals(14, rows("SELECT tablename FROM pg_tables WHERE schemaname='public'").size());
        assertEquals(List.of("1|REPORTER|", "2|TECHNICIAN|", "3|ADMIN|"), rows("SELECT id,name FROM roles ORDER BY id"));
        assertEquals(List.of("CLEANING|", "ELECTRICAL|", "FURNITURE|", "HVAC|", "NETWORK|", "OTHER|", "PLUMBING|"),
                rows("SELECT code FROM categories ORDER BY code"));
        assertEquals(List.of("4|"), rows("INSERT INTO roles(name) VALUES ('TEST_ONLY') RETURNING id"));
        assertEquals(0, f.migrate().migrationsExecuted);
        f.validate();
    }
    @Test
    void explicitBaselinePreservesPopulatedIdentityAndSequences() throws SQLException {
        // Independent fixture: supplied existing schema, not loaded from V1.
        execute("""
            CREATE TABLE roles(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                name VARCHAR(50) NOT NULL UNIQUE);
            CREATE TABLE users(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                full_name VARCHAR(150) NOT NULL, email VARCHAR(255) NOT NULL UNIQUE,
                student_staff_id VARCHAR(50) NOT NULL UNIQUE, department VARCHAR(100) NOT NULL,
                password_hash VARCHAR(255) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
                email_verified BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                role_id BIGINT NOT NULL,
                CONSTRAINT fk_users_role FOREIGN KEY(role_id) REFERENCES roles(id));
            INSERT INTO roles(name) VALUES ('REPORTER'),('TECHNICIAN'),('ADMIN');
            INSERT INTO users(full_name,email,student_staff_id,department,password_hash,role_id)
                VALUES ('Existing','e@example.invalid','E1','Test','synthetic-hash',1);
            """);
        var structure = identityStructure();
        var constraints = identityConstraints();
        String originalUserColumns="id,full_name,email,student_staff_id,department,password_hash,enabled,email_verified,created_at,updated_at,role_id";
        var data = rows("SELECT "+originalUserColumns+" FROM users");
        var sequence = rows("SELECT last_value,is_called FROM roles_id_seq");
        var userSequence = rows("SELECT last_value,is_called FROM users_id_seq");
        var sequenceDefinitions = identitySequences();
        var indexes = identityIndexes();
        var f = flyway(null);
        assertThrows(FlywayException.class, f::migrate);
        f.baseline();
        assertEquals(8, f.migrate().migrationsExecuted);
        var expanded=identityStructure();
        assertEquals(structure, expanded.stream().filter(row->!row.startsWith("users|administratively_disabled|")&&!row.startsWith("users|token_version|")).toList());
        assertEquals(structure.size()+2,expanded.size());
        assertEquals(List.of("boolean|NO|false|"),rows("select data_type,is_nullable,column_default from information_schema.columns where table_schema='public' and table_name='users' and column_name='administratively_disabled'"));
        var expandedConstraints=identityConstraints();
        assertEquals(constraints,expandedConstraints.stream().filter(row->!row.startsWith("users|users_token_version_check|")).toList());
        assertEquals(constraints.size()+1,expandedConstraints.size());
        assertTrue(expandedConstraints.stream().anyMatch(row->row.startsWith("users|users_token_version_check|")&&row.contains("token_version >= 0")));
        assertEquals(data, rows("SELECT "+originalUserColumns+" FROM users"));
        assertEquals(List.of("false|"),rows("SELECT administratively_disabled::text FROM users"));
        assertEquals(List.of("0|"),rows("SELECT token_version FROM users"));
        assertEquals(List.of("bigint|NO|0|"),rows("select data_type,is_nullable,column_default from information_schema.columns where table_schema='public' and table_name='users' and column_name='token_version'"));
        assertEquals(sequence, rows("SELECT last_value,is_called FROM roles_id_seq"));
        assertEquals(userSequence, rows("SELECT last_value,is_called FROM users_id_seq"));
        assertEquals(sequenceDefinitions, identitySequences());
        assertEquals(indexes, identityIndexes());
        var baselineSchema = schemaStructure();
        database = new TestDatabase();
        flyway("1").migrate();
        assertEquals(sequenceDefinitions, identitySequences());
        assertEquals(indexes, identityIndexes());
        assertEquals(structure, identityStructure());
        assertEquals(constraints, identityConstraints());
        flyway(null).migrate();
        assertEquals(baselineSchema, schemaStructure());
    }
    private List<String> identityIndexes() throws SQLException {
        return rows("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'"
                + " AND tablename IN ('roles','users') ORDER BY tablename,indexname");
    }
    private List<String> identitySequences() throws SQLException {
        return rows("""
            SELECT sequencename,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size
            FROM pg_sequences WHERE schemaname='public' AND sequencename IN ('roles_id_seq','users_id_seq')
            ORDER BY sequencename
            """);
    }
    private List<String> schemaStructure() throws SQLException {
        var result = new ArrayList<>(rows("""
            SELECT table_name,column_name,data_type,is_nullable,column_default,is_identity,identity_generation
            FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position
            """));
        result.addAll(rows("""
            SELECT conrelid::regclass::text,conname,pg_get_constraintdef(oid),condeferrable,condeferred
            FROM pg_constraint WHERE connamespace='public'::regnamespace
            ORDER BY conrelid::regclass::text,conname
            """));
        result.addAll(rows("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'"
                + " ORDER BY tablename,indexname"));
        return result;
    }
    private List<String> identityStructure() throws SQLException {
        return rows("""
            SELECT table_name,column_name,data_type,character_maximum_length,is_nullable,column_default,
                   is_identity,identity_generation,identity_start,identity_increment,identity_minimum,
                   identity_maximum,identity_cycle
            FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('roles','users')
            ORDER BY table_name,ordinal_position
            """);
    }
    private List<String> identityConstraints() throws SQLException {
        return rows("""
            SELECT conrelid::regclass::text,conname,pg_get_constraintdef(oid),condeferrable,condeferred
            FROM pg_constraint WHERE conrelid IN ('roles'::regclass,'users'::regclass)
            ORDER BY conrelid::regclass::text,conname
            """);
    }
    @Test
    void conflictingRoleMappingFailsWithoutReplacingRows() throws SQLException {
        flyway("1").migrate();
        execute("INSERT INTO roles(id,name) VALUES (1,'ADMIN')");
        assertThrows(FlywayException.class, () -> flyway(null).migrate());
        assertEquals(List.of("1|ADMIN|"), rows("SELECT id,name FROM roles"));
    }
    @Test
    void locationsAndReferencesRejectInvalidData() throws SQLException {
        flyway(null).migrate(); fixture();
        rejected("23503", "UPDATE maintenance_requests SET room_id=2 WHERE id=1");
        rejected("23503", "UPDATE maintenance_requests SET building_id=999 WHERE id=1");
        rejected("23502", "UPDATE maintenance_requests SET building_id=NULL WHERE id=1");
        rejectedRestriction("UPDATE rooms SET building_id=2 WHERE id=1");
        rejectedRestriction("DELETE FROM buildings WHERE id=1");
        rejectedRestriction("DELETE FROM users WHERE id=1");
        rejected("23505", "INSERT INTO rooms(building_id,code) VALUES (1,'101')");
        rejected("23514", "UPDATE maintenance_requests SET priority='INVALID' WHERE id=1");
        rejected("23514", "UPDATE maintenance_requests SET status='CANCELLED' WHERE id=1");
        assertEquals(List.of("1|null|", "1|1|"), rows("SELECT building_id,room_id FROM maintenance_requests ORDER BY id"));
    }
    @Test
    void assignmentEpisodesAndStatusHistoryRemainIntact() throws SQLException {
        flyway(null).migrate(); fixture();
        execute("INSERT INTO assignments(request_id,technician_id,assigned_by) VALUES (1,2,3)");
        rejected("23505", "INSERT INTO assignments(request_id,technician_id,assigned_by) VALUES (1,2,3)");
        rejected("23514", "UPDATE assignments SET started_at=assigned_at WHERE id=1");
        rejected("23514", "UPDATE assignments SET end_reason='REASSIGNED' WHERE id=1");
        rejected("23514", "UPDATE assignments SET accepted_at=assigned_at - INTERVAL '1 second' WHERE id=1");
        rejected("23514", "UPDATE assignments SET resolved_at=assigned_at WHERE id=1");
        execute("UPDATE assignments SET ended_at=assigned_at,end_reason='REASSIGNED' WHERE id=1");
        execute("INSERT INTO assignments(request_id,technician_id,assigned_by) VALUES (1,2,3)");
        execute("""
            UPDATE assignments SET accepted_at=assigned_at,started_at=assigned_at,resolved_at=assigned_at,
                ended_at=assigned_at,end_reason='RESOLVED',repair_notes='Repaired' WHERE ended_at IS NULL;
            INSERT INTO status_history(request_id,new_status,changed_by) VALUES (1,'OPEN',1);
            """);
        rejected("23505", "INSERT INTO status_history(request_id,new_status,changed_by) VALUES (1,'OPEN',1)");
        rejected("23514", "INSERT INTO status_history(request_id,previous_status,new_status,changed_by) VALUES (1,'OPEN','REJECTED',3)");
        execute("INSERT INTO status_history(request_id,previous_status,new_status,changed_by,reason) VALUES (1,'OPEN','REJECTED',3,'Duplicate')");
        rejectedRestriction("DELETE FROM maintenance_requests WHERE id=1");
        assertEquals(2, rows("SELECT id FROM assignments").size());
        assertEquals(2, rows("SELECT id FROM status_history").size());
    }
    @Test
    void communicationAndVerificationConstraints() throws SQLException {
        flyway(null).migrate(); fixture();
        execute("""
            INSERT INTO comments(request_id,author_id,body) VALUES (1,1,'More details');
            INSERT INTO attachments(request_id,uploaded_by,purpose,storage_provider,storage_key,
                original_filename,media_type,size_bytes)
            VALUES (1,1,'ISSUE_PHOTO','test','photo-1','photo.png','image/png',42);
            INSERT INTO notifications(recipient_id,request_id,event_type,title,message)
            VALUES (1,1,'REQUEST_UPDATED','Updated','Your request changed');
            """);
        rejected("23514", "UPDATE comments SET body=' ' WHERE id=1");
        rejected("23514", "UPDATE attachments SET size_bytes=0 WHERE id=1");
        rejected("23514", "UPDATE notifications SET read_at=created_at - INTERVAL '1 second' WHERE id=1");
        execute("UPDATE notifications SET read_at=created_at WHERE id=1");
        String challenge = """
            INSERT INTO email_verifications(user_id,email,otp_hash,max_attempts,expires_at,resend_available_at)
            VALUES (1,'r@example.invalid','synthetic-hash',5,
                CURRENT_TIMESTAMP + INTERVAL '10 minutes',CURRENT_TIMESTAMP + INTERVAL '1 minute')
            """;
        execute(challenge);
        rejected("23505", challenge);
        rejected("23514", "UPDATE email_verifications SET attempt_count=6 WHERE user_id=1");
        rejected("23514", "UPDATE email_verifications SET expires_at=created_at WHERE user_id=1");
        execute("UPDATE email_verifications SET invalidated_at=created_at WHERE user_id=1");
        execute(challenge);
        assertEquals(2, rows("SELECT id FROM email_verifications").size());
    }
}
