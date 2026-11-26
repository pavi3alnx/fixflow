package com.fixflow.backend;

import com.fixflow.backend.category.*;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.location.*;
import com.fixflow.backend.security.JwtTokens;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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
class CatalogIntegrationTests {
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
    @Autowired BuildingService buildings;
    @Autowired RoomService rooms;
    @Autowired CategoryService categories;
    @Autowired BuildingRepository buildingRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    private String admin, reporter, technician;
    private Long adminId, reporterId;

    @BeforeEach void accounts() {
        var adminUser = account("ADMIN");
        var reporterUser = account("REPORTER");
        adminId = adminUser.getId(); reporterId = reporterUser.getId();
        admin = tokens.issue(adminUser).accessToken(); reporter = tokens.issue(reporterUser).accessToken();
        technician = tokens.issue(account("TECHNICIAN")).accessToken();
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }
    private User account(String role) {
        var user = new User(); user.setFullName("Catalog test " + role);
        user.setEmail(UUID.randomUUID() + "@example.invalid"); user.setStudentStaffId(UUID.randomUUID().toString());
        user.setDepartment("Testing"); user.setPasswordHash("test-only-not-used-for-login");
        user.setRole(roles.findByName(role).orElseThrow()); user.setEnabled(true); user.setEmailVerified(true);
        return users.saveAndFlush(user);
    }
    private String code() { return ("FF05-" + UUID.randomUUID()).toUpperCase(Locale.ROOT); }
    private Map<String, Object> data(String code, String name, boolean active) {
        return new HashMap<>(Map.of("code", code, "name", name, "description", " Details ", "active", active));
    }
    private Map<String, Object> roomData(long buildingId, String code, boolean active) {
        var body = data(code, "Room name", active); body.put("buildingId", buildingId); return body;
    }
    private ResultActions create(String path, Object body, String token) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body)));
    }
    private ResultActions update(String path, Object body, String token) throws Exception {
        return mvc.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body)));
    }
    private ResultActions read(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }
    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }
    private long building(String code, boolean active) throws Exception {
        return body(create("/api/buildings", data(code, "Building", active), admin).andExpect(status().isCreated())).get("id").asLong();
    }
    private long room(long buildingId, String code, boolean active) throws Exception {
        return body(create("/api/rooms", roomData(buildingId, code, active), admin).andExpect(status().isCreated())).get("id").asLong();
    }
    private long category(String code, boolean active) throws Exception {
        return body(create("/api/categories", data(code, "Category", active), admin).andExpect(status().isCreated())).get("id").asLong();
    }
    private void authenticate(Long id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                id.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test void adminCreatesAndUpdatesBuildingWithNormalizedDtoAndLocationHeader() throws Exception {
        String code = code();
        var response = create("/api/buildings", data(" " + code.toLowerCase(Locale.ROOT) + " ", " Name ", true), admin)
                .andExpect(status().isCreated()).andExpect(jsonPath("code").value(code))
                .andExpect(jsonPath("name").value("Name")).andExpect(jsonPath("description").value("Details"))
                .andExpect(jsonPath("rooms").doesNotExist());
        long id = body(response).get("id").asLong();
        response.andExpect(header().string("Location", "/api/buildings/" + id));
        update("/api/buildings/" + id, data(code, "Renamed", false), admin).andExpect(status().isOk())
                .andExpect(jsonPath("id").value(id)).andExpect(jsonPath("active").value(false));
        read("/api/buildings/" + id, admin).andExpect(status().isOk()).andExpect(jsonPath("name").value("Renamed"));
        update("/api/buildings/" + id, data(code, "Renamed", true), admin).andExpect(status().isOk());
        read("/api/buildings/" + id, reporter).andExpect(status().isOk());
    }
    @Test void duplicateBuildingsAndConflictingUpdatesAreSafe409s() throws Exception {
        String code = code(); long first = building(code, true), second = building(code(), true);
        create("/api/buildings", data(code.toLowerCase(Locale.ROOT), "Duplicate", true), admin)
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("detail").value("The operation conflicts with existing data or database constraints."));
        update("/api/buildings/" + second, data(code, "Conflicting", true), admin).andExpect(status().isConflict());
        assertEquals(code, buildingRepository.findById(first).orElseThrow().getCode());
        assertNotEquals(code, buildingRepository.findById(second).orElseThrow().getCode());
    }
    @Test void missingEntitiesAndInvalidParentReferencesReturn404() throws Exception {
        for (String path : List.of("buildings", "rooms", "categories"))
            read("/api/" + path + "/" + Long.MAX_VALUE, admin).andExpect(status().isNotFound());
        create("/api/rooms", roomData(Long.MAX_VALUE, code(), true), admin).andExpect(status().isNotFound());
        update("/api/buildings/" + Long.MAX_VALUE, data(code(), "Missing", true), admin).andExpect(status().isNotFound());
        update("/api/categories/" + Long.MAX_VALUE, data(code(), "Missing", true), admin).andExpect(status().isNotFound());
        read("/api/rooms?buildingId=" + Long.MAX_VALUE, reporter).andExpect(status().isNotFound());
    }
    @Test void adminCreatesAndUpdatesRoomAndOptionalBlankNameBecomesNull() throws Exception {
        long buildingId = building(code(), true); String code = code();
        var input = roomData(buildingId, " " + code.toLowerCase(Locale.ROOT) + " ", true); input.put("name", " ");
        var response = create("/api/rooms", input, admin).andExpect(status().isCreated())
                .andExpect(jsonPath("code").value(code)).andExpect(jsonPath("buildingId").value(buildingId))
                .andExpect(jsonPath("name").isEmpty()).andExpect(jsonPath("available").value(true))
                .andExpect(jsonPath("building").doesNotExist());
        long id = body(response).get("id").asLong();
        response.andExpect(header().string("Location", "/api/rooms/" + id));
        update("/api/rooms/" + id, roomData(buildingId, code, false), admin).andExpect(status().isOk())
                .andExpect(jsonPath("available").value(false));
        read("/api/rooms/" + id, admin).andExpect(status().isOk());
        update("/api/rooms/" + id, roomData(buildingId, code, true), admin).andExpect(status().isOk());
        read("/api/rooms/" + id, technician).andExpect(status().isOk());
    }
    @Test void roomCodeIsUniqueWithinBuildingButMayRepeatAcrossBuildings() throws Exception {
        long first = building(code(), true), second = building(code(), true); String code = code();
        room(first, code, true);
        create("/api/rooms", roomData(first, code.toLowerCase(Locale.ROOT), true), admin).andExpect(status().isConflict());
        room(second, code, true);
        long another = room(first, code(), true);
        update("/api/rooms/" + another, roomData(first, code, true), admin).andExpect(status().isConflict());
    }
    @Test void inactiveBuildingRejectsRoomCreationAndActivation() throws Exception {
        String buildingCode = code(); long parent = building(buildingCode, true); long id = room(parent, code(), false);
        update("/api/buildings/" + parent, data(buildingCode, "Inactive", false), admin).andExpect(status().isOk());
        create("/api/rooms", roomData(parent, code(), true), admin).andExpect(status().isConflict());
        create("/api/rooms", roomData(parent, code(), false), admin).andExpect(status().isConflict());
        update("/api/rooms/" + id, roomData(parent, code(), true), admin).andExpect(status().isConflict());
        update("/api/rooms/" + id, roomData(parent, code(), false), admin).andExpect(status().isOk());
    }
    @Test void roomCannotBeMovedToAnotherBuilding() throws Exception {
        long first = building(code(), true), second = building(code(), true), id = room(first, code(), true);
        update("/api/rooms/" + id, roomData(second, code(), true), admin).andExpect(status().isConflict())
                .andExpect(jsonPath("detail").value("A room's building cannot be changed. Deactivate it and create another room."));
        read("/api/rooms/" + id, admin).andExpect(jsonPath("buildingId").value(first));
    }
    @Test void parentDeactivationHidesRoomsAndReactivationRestoresOnlyActiveChildren() throws Exception {
        String buildingCode = code(); long parent = building(buildingCode, true);
        long activeRoom = room(parent, code(), true), inactiveRoom = room(parent, code(), false);
        update("/api/buildings/" + parent, data(buildingCode, "Disabled parent", false), admin).andExpect(status().isOk());
        for (String token : List.of(reporter, technician)) {
            read("/api/buildings/" + parent, token).andExpect(status().isNotFound());
            read("/api/rooms/" + activeRoom, token).andExpect(status().isNotFound());
            read("/api/rooms?buildingId=" + parent, token).andExpect(status().isNotFound());
            read("/api/rooms?search=" + roomRepository.findById(activeRoom).orElseThrow().getCode(), token)
                    .andExpect(jsonPath("totalElements").value(0));
        }
        read("/api/rooms/" + activeRoom, admin).andExpect(status().isOk())
                .andExpect(jsonPath("active").value(true)).andExpect(jsonPath("available").value(false));
        update("/api/buildings/" + parent, data(buildingCode, "Enabled parent", true), admin).andExpect(status().isOk());
        read("/api/rooms/" + activeRoom, reporter).andExpect(status().isOk());
        read("/api/rooms/" + inactiveRoom, reporter).andExpect(status().isNotFound());
    }
    @Test void roomListsFilterByBuildingAndActiveStatus() throws Exception {
        long parent = building(code(), true), other = building(code(), true);
        long active = room(parent, code(), true), inactive = room(parent, code(), false); room(other, code(), true);
        read("/api/rooms?buildingId=" + parent, reporter).andExpect(status().isOk())
                .andExpect(jsonPath("totalElements").value(1)).andExpect(jsonPath("content[0].id").value(active));
        read("/api/rooms?buildingId=" + parent, admin).andExpect(jsonPath("totalElements").value(2));
        read("/api/rooms?buildingId=" + parent + "&active=false", admin)
                .andExpect(jsonPath("totalElements").value(1)).andExpect(jsonPath("content[0].id").value(inactive));
    }
    @Test void adminCreatesUpdatesAndReactivatesCategoryWithoutChangingResponseShape() throws Exception {
        String code = code();
        var response = create("/api/categories", data(code.toLowerCase(Locale.ROOT), " Category ", true), admin)
                .andExpect(status().isCreated()).andExpect(jsonPath("code").value(code));
        long id = body(response).get("id").asLong();
        response.andExpect(header().string("Location", "/api/categories/" + id));
        update("/api/categories/" + id, data(code, "Updated", false), admin).andExpect(status().isOk());
        read("/api/categories/" + id, admin).andExpect(status().isOk()).andExpect(jsonPath("name").value("Updated"));
        read("/api/categories/" + id, reporter).andExpect(status().isNotFound());
        update("/api/categories/" + id, data(code.toLowerCase(Locale.ROOT), "Restored", true), admin).andExpect(status().isOk());
        JsonNode dto = body(read("/api/categories/" + id, reporter).andExpect(status().isOk()));
        assertEquals(5, dto.size()); assertEquals(id, dto.get("id").asLong());
    }
    @Test void seededCategoriesExistAndCodesStayStable() throws Exception {
        for (String code : List.of("ELECTRICAL", "PLUMBING", "HVAC", "FURNITURE", "NETWORK", "CLEANING", "OTHER")) {
            var category = categoryRepository.findByCode(code).orElseThrow();
            read("/api/categories/" + category.getId(), reporter).andExpect(status().isOk()).andExpect(jsonPath("code").value(code));
            update("/api/categories/" + category.getId(), data(code + "-CHANGED", "Changed", true), admin)
                    .andExpect(status().isConflict());
            assertEquals(code, categoryRepository.findById(category.getId()).orElseThrow().getCode());
        }
    }
    @Test void duplicateCategoryCodesAreRejectedButDisplayNamesNeedNotBeUnique() throws Exception {
        String code = code(); long first = category(code, true);
        create("/api/categories", data(code.toLowerCase(Locale.ROOT), "Category", true), admin).andExpect(status().isConflict());
        long second = category(code(), true);
        assertNotEquals(first, second);
        update("/api/categories/" + first, data(code(), "Rename code", true), admin).andExpect(status().isConflict());
    }
    @Test void reporterAndTechnicianCanReadAllActiveResourceTypes() throws Exception {
        long building = building(code(), true), room = room(building, code(), true), category = category(code(), true);
        for (String token : List.of(reporter, technician)) {
            read("/api/buildings/" + building, token).andExpect(status().isOk());
            read("/api/rooms/" + room, token).andExpect(status().isOk());
            read("/api/categories/" + category, token).andExpect(status().isOk());
            for (String type : List.of("buildings", "rooms", "categories")) read("/api/" + type, token).andExpect(status().isOk());
        }
    }
    @Test void nonAdminsCannotMutateAnyResourceType() throws Exception {
        long building = building(code(), true), room = room(building, code(), true), category = category(code(), true);
        for (String token : List.of(reporter, technician)) {
            for (var entry : Map.of("buildings/" + building, data(code(), "No", false),
                    "rooms/" + room, roomData(building, code(), false), "categories/" + category, data(code(), "No", false)).entrySet()) {
                update("/api/" + entry.getKey(), entry.getValue(), token).andExpect(status().isForbidden());
                create("/api/" + entry.getKey().split("/")[0], entry.getValue(), token).andExpect(status().isForbidden());
            }
        }
        assertTrue(buildingRepository.findById(building).orElseThrow().isActive());
        assertTrue(roomRepository.findById(room).orElseThrow().isActive());
        assertTrue(categoryRepository.findById(category).orElseThrow().isActive());
    }
    @Test void unauthenticatedAccessReturns401AndDeletesRemainDeniedEvenToAdmin() throws Exception {
        for (String type : List.of("buildings", "rooms", "categories")) {
            mvc.perform(get("/api/" + type)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/" + type + "/1")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/" + type).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(put("/api/" + type + "/1").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/" + type + "/1").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void nonAdminsCannotSelectInactiveRecordsEvenByIdOrFilter() throws Exception {
        String buildingCode = code(), categoryCode = code();
        long building = building(buildingCode, false), category = category(categoryCode, false);
        for (String token : List.of(reporter, technician)) {
            read("/api/buildings/" + building, token).andExpect(status().isNotFound());
            read("/api/categories/" + category, token).andExpect(status().isNotFound());
            read("/api/buildings?search=" + buildingCode, token).andExpect(jsonPath("totalElements").value(0));
            read("/api/categories?search=" + categoryCode, token).andExpect(jsonPath("totalElements").value(0));
            for (String type : List.of("buildings", "rooms", "categories"))
                read("/api/" + type + "?active=false", token).andExpect(status().isForbidden());
        }
        read("/api/buildings?search=" + buildingCode + "&active=false", admin).andExpect(jsonPath("totalElements").value(1));
        read("/api/categories?search=" + categoryCode + "&active=false", admin).andExpect(jsonPath("totalElements").value(1));
    }
    @Test void paginationSearchAndSortingAreDeterministicAcrossAllCollections() throws Exception {
        long parent = building(code(), true);
        for (String type : List.of("buildings", "rooms", "categories")) {
            String prefix = ("P-" + UUID.randomUUID().toString().substring(0, 12)).toUpperCase(Locale.ROOT);
            for (String suffix : List.of("C", "A", "B")) {
                var input = type.equals("rooms") ? roomData(parent, prefix + suffix, true) : data(prefix + suffix, "Same name", true);
                create("/api/" + type, input, admin).andExpect(status().isCreated());
            }
            read("/api/" + type + "?search=" + prefix.toLowerCase(Locale.ROOT) + "&size=2&page=0", reporter)
                    .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(3))
                    .andExpect(jsonPath("totalPages").value(2)).andExpect(jsonPath("content.length()").value(2))
                    .andExpect(jsonPath("content[0].code").value(prefix + "A"));
            read("/api/" + type + "?search=" + prefix + "&size=2&page=1&sort=code,desc", reporter)
                    .andExpect(jsonPath("content[0].code").value(prefix + "A"));
            read("/api/" + type + "?search=" + prefix + "&sort=name,asc", reporter)
                    .andExpect(jsonPath("content[0].code").value(prefix + "C")); // Same name: IDs break ties.
            read("/api/" + type + "?search=" + prefix + "&page=20", reporter).andExpect(jsonPath("content.length()").value(0));
        }
    }
    @Test void invalidPaginationSortingAndIdentifiersReturn400() throws Exception {
        for (String type : List.of("buildings", "rooms", "categories")) {
            for (String query : List.of("size=0", "size=101", "page=-1", "page=100001", "size=abc", "sort=description,asc",
                    "sort=name", "sort=building.name,asc", "sort=code,sideways", "active=maybe", "search=" + "x".repeat(101)))
                read("/api/" + type + "?" + query, admin).andExpect(status().isBadRequest());
            read("/api/" + type + "/0", admin).andExpect(status().isBadRequest());
            read("/api/" + type + "/not-a-number", admin).andExpect(status().isBadRequest());
        }
        read("/api/rooms?buildingId=-1", admin).andExpect(status().isBadRequest());
    }
    @Test void dtoValidationRejectsMissingBlankAndOversizedFields() throws Exception {
        for (String type : List.of("buildings", "rooms", "categories")) {
            create("/api/" + type, Map.of(), admin).andExpect(status().isBadRequest());
            var input = type.equals("rooms") ? roomData(1L, " ", true) : data(" ", " ", true);
            create("/api/" + type, input, admin).andExpect(status().isBadRequest());
            input.put("code", "x".repeat(51)); create("/api/" + type, input, admin).andExpect(status().isBadRequest());
            input.put("code", "ß".repeat(30)); create("/api/" + type, input, admin).andExpect(status().isBadRequest());
            input.put("code", code()); input.put("name", "x".repeat(151));
            create("/api/" + type, input, admin).andExpect(status().isBadRequest());
            input.put("name", "Good"); input.put("description", "x".repeat(5001));
            create("/api/" + type, input, admin).andExpect(status().isBadRequest());
            input.remove("description"); input.remove("active"); create("/api/" + type, input, admin).andExpect(status().isBadRequest());
        }
        create("/api/rooms", roomData(-1L, code(), true), admin).andExpect(status().isBadRequest());
    }
    @Test void searchEscapesLikeWildcardsAndMatchesDisplayNames() throws Exception {
        String prefix = "SEARCH-" + UUID.randomUUID().toString().substring(0, 12);
        building(prefix + "_A", true); building(prefix + "XA", true);
        read("/api/buildings?search=" + prefix + "_", reporter).andExpect(status().isOk())
                .andExpect(jsonPath("totalElements").value(1));
        String name = "Name-" + UUID.randomUUID();
        create("/api/categories", data(code(), name, true), admin).andExpect(status().isCreated());
        read("/api/categories?search=" + name.toLowerCase(Locale.ROOT), reporter).andExpect(jsonPath("totalElements").value(1));
    }
    @Test void deactivationPreservesAllHistoricalRequestReferences() throws Exception {
        String buildingCode = code(), roomCode = code(), categoryCode = code();
        long building = building(buildingCode, true), room = room(building, roomCode, true), category = category(categoryCode, true);
        Long request = jdbc.queryForObject("insert into maintenance_requests(reporter_id,building_id,room_id,category_id,title,description,suggested_priority,priority) values (?,?,?,?,'History','Details','LOW','LOW') returning id",
                Long.class, reporterId, building, room, category);
        update("/api/rooms/" + room, roomData(building, roomCode, false), admin).andExpect(status().isOk());
        update("/api/buildings/" + building, data(buildingCode, "Historical building", false), admin).andExpect(status().isOk());
        update("/api/categories/" + category, data(categoryCode, "Historical category", false), admin).andExpect(status().isOk());
        var row = jdbc.queryForMap("select building_id,room_id,category_id from maintenance_requests where id=?", request);
        assertEquals(building, ((Number)row.get("building_id")).longValue());
        assertEquals(room, ((Number)row.get("room_id")).longValue());
        assertEquals(category, ((Number)row.get("category_id")).longValue());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("delete from categories where id=?", category));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("delete from rooms where id=?", room));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("delete from buildings where id=?", building));
    }
    @Test void serviceAuthorizationProtectsDirectInvocation() throws Exception {
        authenticate(reporterId, "REPORTER");
        assertThrows(AccessDeniedException.class, () -> buildings.create(new BuildingRequest(code(), "No", null, true)));
        assertThrows(AccessDeniedException.class, () -> rooms.create(new RoomRequest(1L, code(), null, null, true)));
        assertThrows(AccessDeniedException.class, () -> categories.create(new CategoryRequest(code(), "No", null, true)));
        assertThrows(AccessDeniedException.class, () -> buildings.update(1, new BuildingRequest(code(), "No", null, false)));
        assertThrows(AccessDeniedException.class, () -> rooms.update(1, new RoomRequest(1L, code(), null, null, false)));
        assertThrows(AccessDeniedException.class, () -> categories.update(1, new CategoryRequest(code(), "No", null, false)));
        SecurityContextHolder.clearContext();
        assertThrows(org.springframework.security.core.AuthenticationException.class,
                () -> categories.list(new CatalogQuery(null, null, null, null, null)));
    }
    @Test void concurrentDuplicateBuildingCreationHasOneWinner() throws Exception {
        String code = code();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> create = () -> {
                start.await(); return create("/api/buildings", data(code, "Concurrent", true), admin).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(create); var second = pool.submit(create); start.countDown();
            var statuses = new ArrayList<>(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo); assertEquals(List.of(201, 409), statuses);
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from buildings where code=?", Integer.class, code));
    }
    @Test void concurrentRoomCreationAndBuildingDeactivationRemainConsistent() throws Exception {
        String buildingCode = code(), roomCode = code(); long building = building(buildingCode, true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var room = pool.submit(() -> {
                start.await(); return create("/api/rooms", roomData(building, roomCode, true), admin).andReturn().getResponse().getStatus();
            });
            var deactivate = pool.submit(() -> {
                start.await(); return update("/api/buildings/" + building, data(buildingCode, "Inactive", false), admin)
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertTrue(List.of(201, 409).contains(room.get(10, TimeUnit.SECONDS)));
            assertEquals(200, deactivate.get(10, TimeUnit.SECONDS));
        }
        read("/api/rooms?search=" + roomCode, reporter).andExpect(jsonPath("totalElements").value(0));
    }
    @Test void paginatedRoomReadsAvoidNPlusOneQueries() throws Exception {
        String prefix = "PERF-" + UUID.randomUUID().toString().substring(0, 12);
        for (int i = 0; i < 3; i++) room(building(code(), true), prefix + i, true);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        read("/api/rooms?search=" + prefix + "&size=2", reporter).andExpect(status().isOk())
                .andExpect(jsonPath("content.length()").value(2)).andExpect(jsonPath("totalElements").value(3));
        assertTrue(statistics.getPrepareStatementCount() <= 3,
                "Expected account lookup, joined room page, and count; got " + statistics.getPrepareStatementCount());
    }
}
