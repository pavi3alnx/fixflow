package com.fixflow.backend;

import java.sql.*;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Test-only connections: never reads developer database configuration. */
final class TestDatabase {
    private static final PostgreSQLContainer POSTGRES = start();
    final String username = POSTGRES.getUsername();
    final String password = POSTGRES.getPassword();
    final String url;

    private static PostgreSQLContainer start() {
        var container = new PostgreSQLContainer(System.getProperty("ff02.postgres.image", "postgres:17-alpine"))
                .withDatabaseName("ff02_test_admin");
        container.start();
        return container;
    }

    TestDatabase() throws SQLException {
        String name = "ff02_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
             var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
