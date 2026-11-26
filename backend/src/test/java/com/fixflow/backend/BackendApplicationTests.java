package com.fixflow.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class BackendApplicationTests {
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
    @Test
    void contextLoads() { }
}
