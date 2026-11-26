package com.fixflow.backend;

import com.fixflow.backend.category.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.location.*;
import com.fixflow.backend.request.*;
import com.fixflow.backend.security.JwtTokens;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
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

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MaintenanceRequestIntegrationTests {
    private static final String PATH = "/api/maintenance-requests";
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
    @Autowired JwtTokens tokens;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired CategoryRepository categories;
    @Autowired MaintenanceRequestRepository requests;
    @Autowired StatusHistoryRepository histories;
    @Autowired MaintenanceRequestService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    private User reporter, otherReporter, admin, technician;
    private String reporterToken, otherToken, adminToken, technicianToken;
    private Building building;
    private Room room;
    private Category category;

    @BeforeEach void fixtures() {
        reporter = account("REPORTER"); otherReporter = account("REPORTER");
        admin = account("ADMIN"); technician = account("TECHNICIAN");
        reporterToken = tokens.issue(reporter).accessToken(); otherToken = tokens.issue(otherReporter).accessToken();
        adminToken = tokens.issue(admin).accessToken(); technicianToken = tokens.issue(technician).accessToken();
        building = building();
        room = new Room(); room.setBuilding(building); room.setCode("ROOM-" + UUID.randomUUID());
        room.setName("Test room"); rooms.saveAndFlush(room);
        category = new Category(); category.setCode("CAT-" + UUID.randomUUID()); category.setName("Test category");
        categories.saveAndFlush(category);
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }
    private User account(String role) {
        var user = new User(); user.setFullName("Request test " + role);
        user.setEmail(UUID.randomUUID() + "@example.invalid"); user.setStudentStaffId(UUID.randomUUID().toString());
        user.setDepartment("Testing"); user.setPasswordHash("test-only-not-used-for-login");
        user.setRole(roles.findByName(role).orElseThrow()); user.setEnabled(true); user.setEmailVerified(true);
        return users.saveAndFlush(user);
    }
    private Building building() {
        var building = new Building(); building.setCode("BLDG-" + UUID.randomUUID()); building.setName("Test building");
        return buildings.saveAndFlush(building);
    }
    private Map<String, Object> input(String title, boolean includeRoom) {
        var result = new HashMap<String, Object>(Map.of("title", title, "description", " Problem description ",
                "buildingId", building.getId(), "categoryId", category.getId(), "suggestedPriority", "HIGH"));
        if (includeRoom) result.put("roomId", room.getId());
        return result;
    }
    private ResultActions submit(Object input, String token) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(json.writeValueAsString(input)));
    }
    private ResultActions edit(long id, Object input, String token) throws Exception {
        return mvc.perform(put(PATH + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(json.writeValueAsString(input)));
    }
    private ResultActions read(String suffix, String token) throws Exception {
        return mvc.perform(get(PATH + suffix).header("Authorization", "Bearer " + token));
    }
    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }
    private long create(String title, String token) throws Exception {
        return body(submit(input(title, true), token).andExpect(status().isCreated())).get("id").asLong();
    }
    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getId().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()))));
    }

    @Test void reporterSubmitsRoomRequestAndInitialHistoryAtomically() throws Exception {
        var response = submit(input(" Faulty light ", true), reporterToken).andExpect(status().isCreated())
                .andExpect(jsonPath("title").value("Faulty light")).andExpect(jsonPath("description").value("Problem description"))
                .andExpect(jsonPath("reporterId").value(reporter.getId())).andExpect(jsonPath("status").value("OPEN"))
                .andExpect(jsonPath("suggestedPriority").value("HIGH")).andExpect(jsonPath("priority").value("MEDIUM"))
                .andExpect(jsonPath("building.id").value(building.getId())).andExpect(jsonPath("room.id").value(room.getId()))
                .andExpect(jsonPath("category.id").value(category.getId()));
        JsonNode dto = body(response); long id = dto.get("id").asLong();
        response.andExpect(header().string("Location", PATH + "/" + id));
        assertEquals(dto.get("createdAt"), dto.get("updatedAt"));
        var history = body(read("/" + id + "/history", reporterToken).andExpect(status().isOk()));
        assertEquals(1, history.get("totalElements").asInt());
        assertTrue(history.get("content").get(0).get("previousStatus").isNull());
        assertEquals("OPEN", history.get("content").get(0).get("newStatus").asText());
        assertEquals(dto.get("createdAt"), history.get("content").get(0).get("changedAt"));
        assertEquals(reporter.getId(), jdbc.queryForObject("select changed_by from status_history where request_id=?", Long.class, id));
    }
    @Test void buildingOnlySubmissionWorks() throws Exception {
        submit(input("Building issue", false), reporterToken).andExpect(status().isCreated())
                .andExpect(jsonPath("room").isEmpty());
    }
    @Test void protectedFieldsCannotBeMassAssigned() throws Exception {
        var input = input("Mass assignment test", true);
        input.put("reporterId", otherReporter.getId()); input.put("status", "CLOSED"); input.put("priority", "URGENT");
        input.put("createdAt", "2000-01-01T00:00:00Z"); input.put("updatedAt", "2000-01-01T00:00:00Z");
        input.put("id", Long.MAX_VALUE); input.put("assignmentId", 123); input.put("changedBy", admin.getId());
        var response = submit(input, reporterToken).andExpect(status().isCreated())
                .andExpect(jsonPath("reporterId").value(reporter.getId())).andExpect(jsonPath("priority").value("MEDIUM"))
                .andExpect(jsonPath("status").value("OPEN"));
        JsonNode dto = body(response);
        assertNotEquals(Long.MAX_VALUE, dto.get("id").asLong());
        assertNotEquals("2000-01-01T00:00:00Z", dto.get("createdAt").asText());
        long id = dto.get("id").asLong();
        assertEquals(0, jdbc.queryForObject("select count(*) from assignments where request_id=?", Integer.class, id));
        assertEquals(reporter.getId(), jdbc.queryForObject("select changed_by from status_history where request_id=?", Long.class, id));
    }
    @Test void requiredFieldsAndTextLengthsAreValidated() throws Exception {
        for (String field : List.of("title", "description", "buildingId", "categoryId", "suggestedPriority")) {
            var input = input("Valid", false); input.remove(field);
            submit(input, reporterToken).andExpect(status().isBadRequest());
        }
        for (String field : List.of("title", "description")) {
            var input = input("Valid", false); input.put(field, " \t\n ");
            submit(input, reporterToken).andExpect(status().isBadRequest());
        }
        var input = input("x".repeat(201), false); submit(input, reporterToken).andExpect(status().isBadRequest());
        input.put("title", "Valid"); input.put("description", "x".repeat(10001));
        submit(input, reporterToken).andExpect(status().isBadRequest());
        input.put("description", "Details"); input.put("suggestedPriority", "CRITICAL");
        submit(input, reporterToken).andExpect(status().isBadRequest());
    }
    @Test void invalidLocationCategoryAndNumericReferencesAreRejectedWithoutPartialWrites() throws Exception {
        long count = requests.count(), historyCount = histories.count();
        for (String field : List.of("buildingId", "roomId", "categoryId")) {
            var input = input("Invalid reference", true); input.put(field, Long.MAX_VALUE);
            submit(input, reporterToken).andExpect(status().isNotFound());
            input.put(field, -1); submit(input, reporterToken).andExpect(status().isBadRequest());
        }
        assertEquals(count, requests.count()); assertEquals(historyCount, histories.count());
    }
    @Test void roomBuildingMismatchIs400() throws Exception {
        var input = input("Mismatch", true); input.put("buildingId", building().getId());
        submit(input, reporterToken).andExpect(status().isBadRequest());
    }
    @Test void inactiveBuildingRoomAndCategoryAreRejected() throws Exception {
        for (String table : List.of("buildings", "rooms", "categories")) {
            long id = table.equals("buildings") ? building.getId() : table.equals("rooms") ? room.getId() : category.getId();
            // Table names are fixed test constants, values remain parameters.
            jdbc.update("update " + table + " set active=false where id=?", id);
            submit(input("Inactive reference", true), reporterToken).andExpect(status().isConflict());
            jdbc.update("update " + table + " set active=true where id=?", id);
        }
    }
    @Test void suggestedPriorityIsSeparateFromServerPriorityForEveryValue() throws Exception {
        for (Priority priority : Priority.values()) {
            var input = input("Priority " + priority, false); input.put("suggestedPriority", priority.name());
            submit(input, reporterToken).andExpect(status().isCreated()).andExpect(jsonPath("suggestedPriority").value(priority.name()))
                    .andExpect(jsonPath("priority").value("MEDIUM"));
        }
    }
    @Test void ownerAndAdminCanReadButOtherReporterCannotGuessDetailsOrHistory() throws Exception {
        long id = create("Owned", reporterToken);
        for (String token : List.of(reporterToken, adminToken)) {
            read("/" + id, token).andExpect(status().isOk());
            read("/" + id + "/history", token).andExpect(status().isOk());
        }
        for (String suffix : List.of("/" + id, "/" + id + "/history"))
            read(suffix, otherToken).andExpect(status().isNotFound()).andExpect(jsonPath("detail").value("The requested resource was not found."));
        read("/" + Long.MAX_VALUE, adminToken).andExpect(status().isNotFound());
        read("/" + Long.MAX_VALUE + "/history", adminToken).andExpect(status().isNotFound());
    }
    @Test void reporterListingAndCountStayScopedEvenWithForeignFiltersOrSpoofedOwnerQuery() throws Exception {
        String marker = UUID.randomUUID().toString();
        long own = create(marker + " own", reporterToken); create(marker + " other", otherToken);
        read("?search=" + marker + "&reporterId=" + otherReporter.getId(), reporterToken)
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1))
                .andExpect(jsonPath("content[0].id").value(own));
        read("?search=" + marker, adminToken).andExpect(jsonPath("totalElements").value(2));
        read("?search=" + marker + "&categoryId=" + category.getId(), otherToken)
                .andExpect(jsonPath("totalElements").value(1));
    }
    @Test void technicianOnlyReadsCurrentlyAssignedRequestsAcrossAllReadPaths() throws Exception {
        String marker = UUID.randomUUID().toString(); long assigned = create(marker + " assigned", reporterToken);
        long other = create(marker + " unassigned", reporterToken);
        read("/" + assigned, technicianToken).andExpect(status().isNotFound());
        jdbc.update("insert into assignments(request_id,technician_id,assigned_by) values (?,?,?)", assigned, technician.getId(), admin.getId());
        read("/" + assigned, technicianToken).andExpect(status().isOk());
        read("/" + assigned + "/history", technicianToken).andExpect(status().isOk());
        read("/" + other, technicianToken).andExpect(status().isNotFound());
        read("/" + other + "/history", technicianToken).andExpect(status().isNotFound());
        read("?search=" + marker, technicianToken).andExpect(jsonPath("totalElements").value(1))
                .andExpect(jsonPath("content[0].id").value(assigned));
        jdbc.update("update assignments set ended_at=current_timestamp,end_reason='REASSIGNED' where request_id=?", assigned);
        read("/" + assigned, technicianToken).andExpect(status().isNotFound());
        read("/" + assigned + "/history", technicianToken).andExpect(status().isNotFound());
        read("?search=" + marker, technicianToken).andExpect(jsonPath("totalElements").value(0));
    }
    @Test void nonReportersCannotSubmitOrUpdate() throws Exception {
        long id = create("Owned", reporterToken);
        for (String token : List.of(adminToken, technicianToken)) {
            submit(input("Forbidden", false), token).andExpect(status().isForbidden());
            edit(id, input("Forbidden", false), token).andExpect(status().isForbidden());
        }
    }
    @Test void unauthenticatedRoutesReturn401AndUnsupportedWorkflowRoutesStayDenied() throws Exception {
        for (String suffix : List.of("", "/1", "/1/history")) mvc.perform(get(PATH + suffix)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH + "/1").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/1").header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
        mvc.perform(patch(PATH + "/1/status").header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
    }
    @Test void disabledAndUnverifiedAccountsCannotAccessRequestApis() throws Exception {
        reporter.setEnabled(false); users.saveAndFlush(reporter);
        submit(input("Forbidden", false), reporterToken).andExpect(status().isUnauthorized());
        read("", reporterToken).andExpect(status().isUnauthorized());
        reporter.setEnabled(true); reporter.setEmailVerified(false); users.saveAndFlush(reporter);
        submit(input("Forbidden", false), reporterToken).andExpect(status().isUnauthorized());
    }
    @Test void ownerCanReplaceOpenRequestFieldsWithoutChangingWorkflowOrAuditHistory() throws Exception {
        long id = create("Original", reporterToken); JsonNode before = body(read("/" + id, reporterToken));
        var otherBuilding = building(); var input = input(" Edited ", false); input.put("buildingId", otherBuilding.getId());
        input.put("suggestedPriority", "LOW"); input.put("reporterId", otherReporter.getId()); input.put("priority", "URGENT");
        input.put("status", "CLOSED"); input.put("createdAt", "2000-01-01T00:00:00Z");
        edit(id, input, reporterToken).andExpect(status().isOk()).andExpect(jsonPath("title").value("Edited"))
                .andExpect(jsonPath("room").isEmpty()).andExpect(jsonPath("building.id").value(otherBuilding.getId()))
                .andExpect(jsonPath("reporterId").value(reporter.getId())).andExpect(jsonPath("priority").value("MEDIUM"))
                .andExpect(jsonPath("suggestedPriority").value("LOW")).andExpect(jsonPath("status").value("OPEN"));
        JsonNode after = body(read("/" + id, reporterToken));
        assertEquals(before.get("createdAt"), after.get("createdAt"));
        assertFalse(Instant.parse(after.get("updatedAt").asText()).isBefore(Instant.parse(before.get("updatedAt").asText())));
        assertEquals(1, jdbc.queryForObject("select count(*) from status_history where request_id=?", Integer.class, id));
    }
    @Test void crossReporterUpdateAndNonOpenEditsAreRejected() throws Exception {
        long id = create("Original", reporterToken);
        edit(id, input("Stolen", true), otherToken).andExpect(status().isNotFound());
        for (RequestStatus status : RequestStatus.values()) {
            if (status == RequestStatus.OPEN) continue;
            jdbc.update("update maintenance_requests set status=? where id=?", status.name(), id);
            edit(id, input("Workflow bypass", true), reporterToken).andExpect(status().isConflict());
        }
        assertEquals("Original", jdbc.queryForObject("select title from maintenance_requests where id=?", String.class, id));
        edit(Long.MAX_VALUE, input("Missing", false), reporterToken).andExpect(status().isNotFound());
    }
    @Test void invalidUpdateRollsBackWithoutChangingExistingRequest() throws Exception {
        long id = create("Original", reporterToken);
        var input = input("Changed", true); input.put("categoryId", Long.MAX_VALUE);
        edit(id, input, reporterToken).andExpect(status().isNotFound());
        read("/" + id, reporterToken).andExpect(jsonPath("title").value("Original"));
        input.put("categoryId", category.getId()); input.put("title", " ");
        edit(id, input, reporterToken).andExpect(status().isBadRequest());
    }
    @Test void historicalReadsSurviveDeactivatedReferencesButResubmissionOrEditsRevalidateAvailability() throws Exception {
        long id = create("Historical", reporterToken);
        jdbc.update("update buildings set active=false where id=?", building.getId());
        jdbc.update("update rooms set active=false where id=?", room.getId());
        jdbc.update("update categories set active=false where id=?", category.getId());
        read("/" + id, reporterToken).andExpect(status().isOk()).andExpect(jsonPath("building.active").value(false))
                .andExpect(jsonPath("room.active").value(false)).andExpect(jsonPath("category.active").value(false));
        read("?buildingId=" + building.getId() + "&categoryId=" + category.getId(), reporterToken)
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1));
        read("/" + id + "/history", reporterToken).andExpect(status().isOk());
        submit(input("New", true), reporterToken).andExpect(status().isConflict());
        edit(id, input("New", true), reporterToken).andExpect(status().isConflict());
    }
    @Test void trackingDtosNeverExposeUserCredentialsOrAdministrativeReasons() throws Exception {
        long id = create("Private", reporterToken);
        jdbc.update("insert into status_history(request_id,previous_status,new_status,changed_by,reason) values (?,'OPEN','VERIFIED',?,'internal review note')", id, admin.getId());
        String response = read("/" + id, reporterToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String history = read("/" + id + "/history", reporterToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String forbidden : List.of("passwordHash", "password_hash", "otpHash", "emailVerified", "internal review note", "changedBy", reporter.getEmail())) {
            assertFalse(response.contains(forbidden)); assertFalse(history.contains(forbidden));
        }
    }
    @Test void defaultOrderingPaginationSearchAndStableTieBreakersWork() throws Exception {
        String marker = UUID.randomUUID().toString(); long first = create(marker + " A", reporterToken);
        long second = create(marker + " B", reporterToken), third = create(marker + " C", reporterToken);
        jdbc.update("update maintenance_requests set created_at='2026-01-01T00:00:00Z' where id in (?,?,?)", first, second, third);
        read("?search=" + marker + "&size=2", reporterToken).andExpect(status().isOk())
                .andExpect(jsonPath("totalElements").value(3)).andExpect(jsonPath("totalPages").value(2))
                .andExpect(jsonPath("content[0].id").value(third)).andExpect(jsonPath("content[1].id").value(second));
        read("?search=" + marker + "&size=2&page=1", reporterToken).andExpect(jsonPath("content[0].id").value(first));
        read("?search=" + marker + "&sort=title,asc", reporterToken).andExpect(jsonPath("content[0].id").value(first));
        read("?search=" + marker + "&page=100", reporterToken).andExpect(jsonPath("content.length()").value(0));
    }
    @Test void statusBuildingCategoryAndDateFiltersCombineWithOwnership() throws Exception {
        String marker = UUID.randomUUID().toString(); long first = create(marker, reporterToken);
        long second = create(marker, reporterToken); create(marker, otherToken);
        jdbc.update("update maintenance_requests set status='VERIFIED',created_at='2026-02-01T00:00:00Z' where id=?", first);
        jdbc.update("update maintenance_requests set created_at='2026-02-02T00:00:00Z' where id=?", second);
        read("?search=" + marker + "&status=VERIFIED&buildingId=" + building.getId() + "&categoryId=" + category.getId()
                + "&submittedFrom=2026-02-01T00:00:00Z&submittedTo=2026-02-02T00:00:00Z", reporterToken)
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1)).andExpect(jsonPath("content[0].id").value(first));
        read("?search=" + marker + "&buildingId=" + Long.MAX_VALUE, reporterToken).andExpect(jsonPath("totalElements").value(0));
        read("?search=" + marker + "&categoryId=" + Long.MAX_VALUE, reporterToken).andExpect(jsonPath("totalElements").value(0));
    }
    @Test void invalidQueryParametersAndHistoryPaginationReturn400() throws Exception {
        for (String query : List.of("size=0", "size=101", "page=-1", "page=100001", "page=abc", "sort=reporter.email,asc", "sort=createdAt",
                "sort=id,sideways", "status=INVALID", "buildingId=-1", "categoryId=0", "submittedFrom=not-a-date",
                "submittedFrom=2026-02-02T00:00:00Z&submittedTo=2026-02-01T00:00:00Z",
                "submittedFrom=2026-02-01T00:00:00Z&submittedTo=2026-02-01T00:00:00Z", "search=" + "x".repeat(101)))
            read("?" + query, reporterToken).andExpect(status().isBadRequest());
        long id = create("History", reporterToken);
        for (String query : List.of("size=0", "size=101", "page=-1", "page=100001"))
            read("/" + id + "/history?" + query, reporterToken).andExpect(status().isBadRequest());
        read("/0", reporterToken).andExpect(status().isBadRequest());
        read("/not-a-number", reporterToken).andExpect(status().isBadRequest());
    }
    @Test void searchTreatsLikeWildcardsAsLiteralText() throws Exception {
        String marker = UUID.randomUUID().toString();
        create(marker + "_A", reporterToken); create(marker + "XA", reporterToken);
        read("?search=" + marker + "_", reporterToken).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1));
        String descriptionMarker = UUID.randomUUID().toString(); var input = input("Other title", false);
        input.put("description", descriptionMarker.toUpperCase(Locale.ROOT)); submit(input, reporterToken).andExpect(status().isCreated());
        read("?search=" + descriptionMarker.toLowerCase(Locale.ROOT), reporterToken).andExpect(jsonPath("totalElements").value(1));
    }
    @Test void historyPaginationIsChronologicalAndUsesIdForTimestampTies() throws Exception {
        long id = create("History", reporterToken);
        jdbc.update("insert into status_history(request_id,previous_status,new_status,changed_by,changed_at) values (?,'OPEN','VERIFIED',?,current_timestamp+interval '1 day')", id, admin.getId());
        jdbc.update("insert into status_history(request_id,previous_status,new_status,changed_by,changed_at) values (?,'VERIFIED','ASSIGNED',?,current_timestamp+interval '2 days')", id, admin.getId());
        jdbc.update("update status_history set changed_at='2030-01-01T00:00:00Z' where request_id=?", id);
        read("/" + id + "/history?size=2", reporterToken).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(3))
                .andExpect(jsonPath("content[0].newStatus").value("OPEN")).andExpect(jsonPath("content[1].newStatus").value("VERIFIED"));
        read("/" + id + "/history?size=2&page=1", reporterToken).andExpect(jsonPath("content[0].newStatus").value("ASSIGNED"));
    }
    @Test void historyInsertFailureRollsBackRequestAndHistory() throws Exception {
        String marker = "fail-" + UUID.randomUUID(); long count = requests.count(), historyCount = histories.count();
        // This trigger exists only in this class's disposable PostgreSQL database.
        jdbc.execute("create function ff06_reject_history() returns trigger language plpgsql as $$ begin "
                + "if exists (select 1 from maintenance_requests where id=NEW.request_id and title='" + marker + "') then "
                + "raise exception 'synthetic history failure' using errcode='23514'; end if; return NEW; end $$");
        jdbc.execute("create trigger ff06_reject_history before insert on status_history for each row execute function ff06_reject_history()");
        try {
            submit(input(marker, true), reporterToken).andExpect(status().isConflict());
            assertEquals(count, requests.count()); assertEquals(historyCount, histories.count());
        } finally {
            jdbc.execute("drop trigger ff06_reject_history on status_history"); jdbc.execute("drop function ff06_reject_history()");
        }
    }
    @Test void concurrentSubmissionsHaveUniqueDatabaseIdsAndExactlyOneInitialHistoryEach() throws Exception {
        String marker = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 3; i++) futures.add(pool.submit(() -> {
                start.await(); return body(submit(input(marker, true), reporterToken).andExpect(status().isCreated())).get("id").asLong();
            }));
            start.countDown(); var ids = new HashSet<Long>();
            for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS));
            assertEquals(3, ids.size());
            for (long id : ids) assertEquals(1, jdbc.queryForObject("select count(*) from status_history where request_id=? and previous_status is null and new_status='OPEN'", Integer.class, id));
        }
    }
    @Test void concurrentBuildingDeactivationAndSubmissionSerializeSafely() throws Exception {
        String marker = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var submission = pool.submit(() -> { start.await(); return submit(input(marker, true), reporterToken).andReturn().getResponse().getStatus(); });
            var deactivate = pool.submit(() -> {
                start.await(); return mvc.perform(put("/api/buildings/" + building.getId()).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("code", building.getCode(), "name", building.getName(), "active", false))))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown(); int outcome = submission.get(10, TimeUnit.SECONDS); assertEquals(200, deactivate.get(10, TimeUnit.SECONDS));
            assertTrue(List.of(201, 409).contains(outcome));
            int count = jdbc.queryForObject("select count(*) from maintenance_requests where title=?", Integer.class, marker);
            assertEquals(outcome == 201 ? 1 : 0, count);
            read("?search=" + marker, reporterToken).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(count));
        }
    }
    @Test void requestListsFetchReferencesWithoutNPlusOneQueries() throws Exception {
        String marker = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            building = building();
            submit(input(marker + i, false), reporterToken).andExpect(status().isCreated());
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        // The requests below use no room because the fixture room belongs to the original building.
        read("?search=" + marker + "&size=2", reporterToken).andExpect(status().isOk())
                .andExpect(jsonPath("totalElements").value(3));
        assertTrue(statistics.getPrepareStatementCount() <= 3,
                "Expected account, joined page and count queries; got " + statistics.getPrepareStatementCount());
    }
    @Test void directServiceInvocationStillEnforcesRolesAndOwnership() throws Exception {
        long id = create("Owned", reporterToken);
        var details = new RequestDetails("No", "No", building.getId(), null, category.getId(), Priority.LOW);
        authenticate(admin); assertThrows(AccessDeniedException.class, () -> service.create(details));
        assertThrows(AccessDeniedException.class, () -> service.update(id, details));
        authenticate(technician); assertThrows(AccessDeniedException.class, () -> service.create(details));
        authenticate(otherReporter);
        assertThrows(com.fixflow.backend.common.api.ResourceNotFoundException.class, () -> service.get(id));
        assertThrows(com.fixflow.backend.common.api.ResourceNotFoundException.class, () -> service.history(id, 0, 20));
        assertThrows(com.fixflow.backend.common.api.ResourceNotFoundException.class, () -> service.update(id, details));
        SecurityContextHolder.clearContext();
        assertThrows(org.springframework.security.core.AuthenticationException.class, () -> service.create(details));
    }
}
