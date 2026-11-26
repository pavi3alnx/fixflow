package com.fixflow.backend;

import com.fixflow.backend.category.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.location.*;
import com.fixflow.backend.request.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class PersistenceFoundationTests {
    private static final TestDatabase DATABASE = database();
    private static TestDatabase database() {
        try { return new TestDatabase(); }
        catch (java.sql.SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.username);
        registry.add("spring.datasource.password", () -> DATABASE.password);
    }
    @Autowired CategoryRepository categories;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired MaintenanceRequestRepository requests;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void validatesAllDomainMappingsAndQueriesSeededCategory() {
        assertEquals(12, entityManager.getMetamodel().getEntities().size());
        assertEquals("Electrical", categories.findByCode("ELECTRICAL").orElseThrow().getName());
    }

    @Test
    void roundTripsBuildingAndOptionalRoomAndRollsBackWholeTransaction() {
        var tx = new TransactionTemplate(transactions);
        long before = requests.count();
        Long[] ids = new Long[2];
        tx.executeWithoutResult(status -> {
            var building = new Building();
            building.setCode("FF03"); building.setName("Test building");
            buildings.saveAndFlush(building);
            var room = new Room(); room.setBuilding(building); room.setCode("101");
            rooms.saveAndFlush(room);
            var user = new User();
            user.setFullName("Reporter"); user.setEmail("ff03@example.invalid");
            user.setStudentStaffId("FF03"); user.setDepartment("Test");
            user.setPasswordHash("synthetic-private-hash"); user.setRole(roles.findById(1L).orElseThrow());
            users.saveAndFlush(user);
            var category = categories.findByCode("ELECTRICAL").orElseThrow();
            var buildingRequest = request(user, building, category);
            ids[0] = requests.saveAndFlush(buildingRequest).getId();
            var roomRequest = request(user, building, category); roomRequest.setRoom(room);
            ids[1] = requests.saveAndFlush(roomRequest).getId();
            entityManager.clear();
            var loadedBuilding = requests.findById(ids[0]).orElseThrow();
            assertNull(loadedBuilding.getRoom());
            assertEquals(building.getId(), loadedBuilding.getBuilding().getId());
            assertEquals(RequestStatus.OPEN, loadedBuilding.getStatus());
            assertNotNull(loadedBuilding.getCreatedAt());
            var loadedRoom = requests.findById(ids[1]).orElseThrow();
            assertEquals(room.getId(), loadedRoom.getRoom().getId());
            assertEquals(building.getId(), loadedRoom.getBuilding().getId());
            var oldTimestamp = loadedRoom.getUpdatedAt();
            loadedRoom.setTitle("Updated title"); requests.flush();
            assertFalse(loadedRoom.getUpdatedAt().isBefore(oldTimestamp));
            status.setRollbackOnly();
        });
        assertEquals(before, requests.count());
        assertTrue(requests.findById(ids[0]).isEmpty());
        assertTrue(requests.findById(ids[1]).isEmpty());
        assertEquals(0, buildings.count());
        assertEquals(0, users.count());
    }
    private MaintenanceRequest request(User reporter, Building building, Category category) {
        var request = new MaintenanceRequest();
        request.setReporter(reporter); request.setBuilding(building); request.setCategory(category);
        request.setTitle("Fault"); request.setDescription("Details");
        request.setSuggestedPriority(Priority.LOW); request.setPriority(Priority.HIGH);
        return request;
    }
}
