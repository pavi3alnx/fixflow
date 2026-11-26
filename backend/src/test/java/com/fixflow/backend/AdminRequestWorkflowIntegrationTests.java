package com.fixflow.backend;

import com.fixflow.backend.identity.*;
import com.fixflow.backend.location.*;
import com.fixflow.backend.request.*;
import com.fixflow.backend.request.AdminWorkflowContracts.*;
import com.fixflow.backend.security.JwtTokens;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminRequestWorkflowIntegrationTests {
    private static final String PUBLIC = "/api/maintenance-requests";
    private static final String ADMIN = "/api/admin/maintenance-requests/";
    private static final TestDatabase DATABASE = database();
    private static TestDatabase database() {
        try { return new TestDatabase(); }
        catch (java.sql.SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.username);
        registry.add("spring.datasource.password", () -> DATABASE.password);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired BuildingRepository buildings;
    @Autowired JwtTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminRequestWorkflowService workflow;
    private User admin, reporter, technician;
    private String adminToken, reporterToken, technicianToken;
    private long id, buildingId;

    @BeforeEach void fixture() throws Exception {
        admin = account("ADMIN"); reporter = account("REPORTER"); technician = account("TECHNICIAN");
        adminToken = tokens.issue(admin).accessToken(); reporterToken = tokens.issue(reporter).accessToken();
        technicianToken = tokens.issue(technician).accessToken();
        var building = new Building(); building.setCode("FF07-" + UUID.randomUUID()); building.setName("Test building");
        buildingId = buildings.saveAndFlush(building).getId();
        id = json.readTree(mvc.perform(post(PUBLIC).header("Authorization", "Bearer " + reporterToken).contentType("application/json")
                .content(json.writeValueAsString(editBody("Original")))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }
    private User account(String role) {
        var user = new User(); user.setFullName("Workflow " + role); user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setStudentStaffId(UUID.randomUUID().toString()); user.setDepartment("Testing"); user.setPasswordHash("test-only-unused");
        user.setEnabled(true); user.setEmailVerified(true); user.setRole(roles.findByName(role).orElseThrow());
        return users.saveAndFlush(user);
    }
    private Map<String,Object> editBody(String title) {
        return Map.of("title", title, "description", "Description", "buildingId", buildingId, "categoryId", 1, "suggestedPriority", "HIGH");
    }
    private JsonNode current() throws Exception {
        return json.readTree(mvc.perform(get(PUBLIC + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String stamp() throws Exception { return current().get("updatedAt").asText(); }
    private Map<String,Object> transition(String state, String stamp, String reason) {
        var body = new HashMap<String,Object>(); body.put("status", state); body.put("expectedUpdatedAt", stamp);
        if (reason != null) body.put("reason", reason); return body;
    }
    private ResultActions change(String action, Object body, String token) throws Exception {
        return mvc.perform(patch(ADMIN + id + "/" + action).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body)));
    }
    private long historyCount() { return jdbc.queryForObject("select count(*) from status_history where request_id=?", Long.class, id); }
    private void resolvedFixture(String status) {
        jdbc.update("insert into assignments(request_id,technician_id,assigned_by,assigned_at,accepted_at,started_at,resolved_at,ended_at,end_reason,repair_notes) "
                + "values (?,?,?,current_timestamp,current_timestamp,current_timestamp,current_timestamp,current_timestamp,'RESOLVED','Repaired')", id, technician.getId(), admin.getId());
        jdbc.update("update maintenance_requests set status=? where id=?", status, id);
    }
    @Test void verificationRecordsActorReasonAndPreservesOriginalFieldsAndInitialHistory() throws Exception {
        JsonNode before = current(); var body = transition("VERIFIED", stamp(), " Reviewed by administrator ");
        body.put("actorId", reporter.getId()); body.put("changedAt", "2000-01-01T00:00:00Z"); body.put("reporterId", admin.getId());
        change("verification", body, adminToken).andExpect(status().isOk()).andExpect(jsonPath("status").value("VERIFIED"));
        JsonNode after = current();
        for (String field : List.of("reporterId", "title", "description", "createdAt", "suggestedPriority", "priority"))
            assertEquals(before.get(field), after.get(field));
        assertTrue(Instant.parse(after.get("updatedAt").asText()).isAfter(Instant.parse(before.get("updatedAt").asText())));
        assertEquals(2, historyCount());
        var entry = jdbc.queryForMap("select previous_status,new_status,changed_by,reason from status_history where request_id=? and previous_status is not null", id);
        assertEquals("OPEN", entry.get("previous_status")); assertEquals("VERIFIED", entry.get("new_status"));
        assertEquals(admin.getId(), ((Number)entry.get("changed_by")).longValue()); assertEquals("Reviewed by administrator", entry.get("reason"));
        var audit = json.readTree(mvc.perform(get(ADMIN + id + "/history?size=1&page=1").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(after.get("updatedAt"), audit.get("content").get(0).get("changedAt"));
    }
    @Test void rejectionNeedsMeaningfulReasonAndIsTerminal() throws Exception {
        for (String reason : List.of("", "   ", "bad"))
            change("verification", transition("REJECTED", stamp(), reason), adminToken).andExpect(status().isBadRequest());
        change("verification", transition("REJECTED", stamp(), null), adminToken).andExpect(status().isBadRequest());
        assertEquals(1, historyCount());
        change("verification", transition("REJECTED", stamp(), "Duplicate submission"), adminToken).andExpect(status().isOk());
        change("status", transition("VERIFIED", stamp(), "Try again"), adminToken).andExpect(status().isConflict());
        assertEquals(2, historyCount());
    }
    @Test void invalidVerificationStatesMissingRequestsAndRepeatedReviewAreRejected() throws Exception {
        change("verification", transition("ASSIGNED", stamp(), null), adminToken).andExpect(status().isBadRequest());
        mvc.perform(patch(ADMIN + Long.MAX_VALUE + "/verification").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(transition("VERIFIED", stamp(), null))))
                .andExpect(status().isNotFound());
        String old = stamp(); change("verification", transition("VERIFIED", old, null), adminToken).andExpect(status().isOk());
        change("verification", transition("VERIFIED", old, null), adminToken).andExpect(status().isConflict());
        change("verification", transition("REJECTED", stamp(), "Contradictory review"), adminToken).andExpect(status().isConflict());
        assertEquals(2, historyCount());
    }
    @Test void prioritySupportsAllValuesPreservesSuggestionAndDoesNotFakeHistory() throws Exception {
        for (Priority priority : Priority.values())
            change("priority", Map.of("priority", priority.name(), "expectedUpdatedAt", stamp()), adminToken)
                    .andExpect(status().isOk()).andExpect(jsonPath("priority").value(priority.name()))
                    .andExpect(jsonPath("suggestedPriority").value("HIGH"));
        assertEquals(1, historyCount());
    }
    @Test void samePriorityIsFreshNoOpButStalePriorityWritesConflict() throws Exception {
        String before = stamp();
        change("priority", Map.of("priority", "MEDIUM", "expectedUpdatedAt", before), adminToken).andExpect(status().isOk());
        assertEquals(before, stamp());
        change("priority", Map.of("priority", "URGENT", "expectedUpdatedAt", before), adminToken).andExpect(status().isOk());
        change("priority", Map.of("priority", "URGENT", "expectedUpdatedAt", before), adminToken).andExpect(status().isConflict());
        assertEquals(1, historyCount());
    }
    @Test void priorityRestrictionsApplyToAllResolvedAndTerminalStates() throws Exception {
        for (String status : List.of("RESOLVED", "CONFIRMED", "CLOSED", "REJECTED")) {
            jdbc.update("update maintenance_requests set status=? where id=?", status, id);
            change("priority", Map.of("priority", "URGENT", "expectedUpdatedAt", stamp()), adminToken).andExpect(status().isConflict());
        }
    }
    @Test void supportedPostResolutionTransitionsRequireEvidenceAndRecordHistory() throws Exception {
        resolvedFixture("RESOLVED");
        change("status", transition("REOPENED", stamp(), "Repair did not fix problem"), adminToken).andExpect(status().isOk());
        change("status", transition("VERIFIED", stamp(), "Reviewed for reassignment"), adminToken).andExpect(status().isOk());
        jdbc.update("update maintenance_requests set status='CONFIRMED' where id=?", id);
        change("status", transition("CLOSED", stamp(), "Completed review"), adminToken).andExpect(status().isOk());
        assertEquals(4, historyCount());
        change("status", transition("REOPENED", stamp(), "Cannot reopen terminal"), adminToken).andExpect(status().isConflict());
    }
    @Test void incompleteResolutionOrActiveAssignmentsCannotBeBypassed() throws Exception {
        jdbc.update("update maintenance_requests set status='RESOLVED' where id=?", id);
        change("status", transition("REOPENED", stamp(), "Needs more work"), adminToken).andExpect(status().isConflict());
        resolvedFixture("RESOLVED");
        jdbc.update("insert into assignments(request_id,technician_id,assigned_by) values (?,?,?)", id, technician.getId(), admin.getId());
        change("status", transition("REOPENED", stamp(), "Needs more work"), adminToken).andExpect(status().isConflict());
        assertEquals(1, historyCount());
    }
    @Test void noSkippedStagesNoSameStateAndNoAlternateReviewRoute() throws Exception {
        change("status", transition("VERIFIED", stamp(), null), adminToken).andExpect(status().isConflict());
        change("verification", transition("VERIFIED", stamp(), null), adminToken).andExpect(status().isOk());
        for (String target : List.of("VERIFIED", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CONFIRMED", "CLOSED", "REJECTED"))
            change("status", transition(target, stamp(), "Attempted bypass"), adminToken).andExpect(status().isConflict());
        assertEquals(2, historyCount());
    }
    @Test void invalidDtoValuesAndOversizedNotesReturn400() throws Exception {
        change("verification", Map.of("status", "VERIFIED"), adminToken).andExpect(status().isBadRequest());
        change("verification", transition("NO_SUCH_STATUS", stamp(), null), adminToken).andExpect(status().isBadRequest());
        change("verification", transition("VERIFIED", stamp(), "x".repeat(2001)), adminToken).andExpect(status().isBadRequest());
        change("priority", Map.of("priority", "CRITICAL", "expectedUpdatedAt", stamp()), adminToken).andExpect(status().isBadRequest());
        change("priority", Map.of("priority", "HIGH", "expectedUpdatedAt", "not-a-time"), adminToken).andExpect(status().isBadRequest());
        change("status", Map.of("expectedUpdatedAt", stamp()), adminToken).andExpect(status().isBadRequest());
    }
    @Test void allAdministrativeRoutesDenyOtherRolesAndUnauthenticatedRequests() throws Exception {
        for (String action : List.of("verification", "priority", "status")) {
            var body = action.equals("priority") ? Map.of("priority", "URGENT", "expectedUpdatedAt", stamp())
                    : transition("VERIFIED", stamp(), null);
            for (String token : List.of(reporterToken, technicianToken)) change(action, body, token).andExpect(status().isForbidden());
            mvc.perform(patch(ADMIN + id + "/" + action).contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
        }
        for (String token : List.of(reporterToken, technicianToken))
            mvc.perform(get(ADMIN + id + "/history").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN + id + "/history")).andExpect(status().isUnauthorized());
    }
    @Test void privilegedHistoryShowsAuditWithoutChangingPublicHistoryShape() throws Exception {
        change("verification", transition("REJECTED", stamp(), "Internal review details"), adminToken).andExpect(status().isOk());
        mvc.perform(get(ADMIN + id + "/history?size=1&page=1").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(2))
                .andExpect(jsonPath("content[0].actorId").value(admin.getId()))
                .andExpect(jsonPath("content[0].reason").value("Internal review details"));
        String publicResponse = mvc.perform(get(PUBLIC + "/" + id + "/history").header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(publicResponse.contains("Internal review details")); assertFalse(publicResponse.contains("actorId"));
        mvc.perform(get(ADMIN + id + "/history?size=101").header("Authorization", "Bearer " + adminToken)).andExpect(status().isBadRequest());
        mvc.perform(get(ADMIN + Long.MAX_VALUE + "/history").header("Authorization", "Bearer " + adminToken)).andExpect(status().isNotFound());
        mvc.perform(delete(ADMIN + id + "/history/1").header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
    }
    @Test void officialPriorityFilteringUsesExistingScopedListing() throws Exception {
        change("priority", Map.of("priority", "URGENT", "expectedUpdatedAt", stamp()), adminToken).andExpect(status().isOk());
        mvc.perform(get(PUBLIC + "?priority=URGENT&buildingId=" + buildingId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1)).andExpect(jsonPath("content[0].id").value(id));
        mvc.perform(get(PUBLIC + "?priority=LOW&buildingId=" + buildingId).header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(0));
        mvc.perform(get(PUBLIC + "?priority=CRITICAL").header("Authorization", "Bearer " + adminToken)).andExpect(status().isBadRequest());
    }
    @Test void historyFailureRollsBackStatusTimestampAndAuditEntry() throws Exception {
        String before = stamp();
        jdbc.execute("create function ff07_fail_history() returns trigger language plpgsql as $$ begin if NEW.request_id=" + id
                + " and NEW.previous_status is not null then raise exception 'synthetic history failure' using errcode='23514'; end if; return NEW; end $$");
        jdbc.execute("create trigger ff07_fail_history before insert on status_history for each row execute function ff07_fail_history()");
        try {
            change("verification", transition("VERIFIED", before, "Review"), adminToken).andExpect(status().isConflict());
            assertEquals("OPEN", current().get("status").asText()); assertEquals(before, stamp()); assertEquals(1, historyCount());
        } finally { jdbc.execute("drop trigger ff07_fail_history on status_history"); jdbc.execute("drop function ff07_fail_history()"); }
    }
    private List<Integer> concurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); }); start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
    private void assertOneConflict(List<Integer> statuses) {
        var sorted = new ArrayList<>(statuses); sorted.sort(Integer::compareTo); assertEquals(List.of(200, 409), sorted);
    }
    @Test void concurrentContradictoryReviewsHaveOneWinnerAndOneHistoryEntry() throws Exception {
        String before = stamp();
        assertOneConflict(concurrently(
                () -> change("verification", transition("VERIFIED", before, null), adminToken).andReturn().getResponse().getStatus(),
                () -> change("verification", transition("REJECTED", before, "Duplicate request"), adminToken).andReturn().getResponse().getStatus()));
        assertEquals(2, historyCount());
    }
    @Test void concurrentPrioritiesDoNotLoseUpdates() throws Exception {
        String before = stamp();
        assertOneConflict(concurrently(
                () -> change("priority", Map.of("priority", "HIGH", "expectedUpdatedAt", before), adminToken).andReturn().getResponse().getStatus(),
                () -> change("priority", Map.of("priority", "LOW", "expectedUpdatedAt", before), adminToken).andReturn().getResponse().getStatus()));
        assertTrue(Set.of("HIGH", "LOW").contains(current().get("priority").asText())); assertEquals(1, historyCount());
    }
    @Test void concurrentStatusChangesDoNotDuplicateHistory() throws Exception {
        resolvedFixture("RESOLVED"); String before = stamp();
        assertOneConflict(concurrently(
                () -> change("status", transition("REOPENED", before, "Failure recurred"), adminToken).andReturn().getResponse().getStatus(),
                () -> change("status", transition("REOPENED", before, "Still not repaired"), adminToken).andReturn().getResponse().getStatus()));
        assertEquals(2, historyCount());
    }
    @Test void reporterEditAndVerificationSerializeAndRejectTheStaleOperation() throws Exception {
        String before = stamp();
        List<Integer> outcomes = concurrently(
                () -> change("verification", transition("VERIFIED", before, null), adminToken).andReturn().getResponse().getStatus(),
                () -> mvc.perform(put(PUBLIC + "/" + id).header("Authorization", "Bearer " + reporterToken).contentType("application/json")
                        .content(json.writeValueAsString(editBody("Edited")))).andReturn().getResponse().getStatus());
        assertOneConflict(outcomes);
        assertEquals(outcomes.get(0) == 200 ? "VERIFIED" : "OPEN", current().get("status").asText());
        assertEquals(outcomes.get(1) == 200 ? "Edited" : "Original", current().get("title").asText());
    }
    @Test void laterReporterEditsCannotOverwriteAdministrativePriority() throws Exception {
        change("priority", Map.of("priority", "URGENT", "expectedUpdatedAt", stamp()), adminToken).andExpect(status().isOk());
        var body = new HashMap<String,Object>(editBody("Edited")); body.put("priority", "LOW");
        mvc.perform(put(PUBLIC + "/" + id).header("Authorization", "Bearer " + reporterToken).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("priority").value("URGENT"));
    }
    @Test void serviceAuthorizationIsIndependentOfHttpRules() throws Exception {
        for (var user : List.of(reporter, technician)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getId().toString(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()))));
            assertThrows(AccessDeniedException.class, () -> workflow.verify(id, new Transition(RequestStatus.VERIFIED, null, Instant.now())));
            assertThrows(AccessDeniedException.class, () -> workflow.status(id, new Transition(RequestStatus.CLOSED, null, Instant.now())));
            assertThrows(AccessDeniedException.class, () -> workflow.priority(id, new PriorityChange(Priority.HIGH, Instant.now())));
            assertThrows(AccessDeniedException.class, () -> workflow.history(id, 0, 20));
        }
    }
}
