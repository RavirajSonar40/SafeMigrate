package com.safemigrate.core.wal;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Manages PostgreSQL physical connections with replication capabilities and replication slot management.
 */
public class PostgresReplicationConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(PostgresReplicationConnectionFactory.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresReplicationConnectionFactory(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Opens a dedicated PostgreSQL connection configured for logical replication.
     */
    public Connection createReplicationConnection() throws SQLException {
        Properties props = new Properties();
        PGProperty.USER.set(props, username);
        PGProperty.PASSWORD.set(props, password);
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "10.0");
        PGProperty.REPLICATION.set(props, "database");
        // Prefer query mode that doesn't conflict with replication streaming
        PGProperty.PREFER_QUERY_MODE.set(props, "simple");

        log.info("Establishing replication connection to: {}", jdbcUrl);
        return DriverManager.getConnection(jdbcUrl, props);
    }

    /**
     * Ensures the requested logical replication slot exists, creating it if necessary.
     */
    public void ensureReplicationSlotExists(String slotName, String outputPlugin) throws SQLException {
        // Use a standard non-replication connection to inspect and create slot if needed
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            boolean exists = false;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT 1 FROM pg_replication_slots WHERE slot_name = ?")) {
                stmt.setString(1, slotName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                    }
                }
            }

            if (!exists) {
                log.info("Creating logical replication slot '{}' with output plugin '{}'", slotName, outputPlugin);
                try (PreparedStatement createStmt = conn.prepareStatement(
                        "SELECT pg_create_logical_replication_slot(?, ?)")) {
                    createStmt.setString(1, slotName);
                    createStmt.setString(2, outputPlugin);
                    createStmt.execute();
                    log.info("Successfully created replication slot '{}'", slotName);
                }
            } else {
                log.info("Logical replication slot '{}' already exists", slotName);
            }
        }
    }

    /**
     * Drops a replication slot if it exists.
     */
    public void dropReplicationSlot(String slotName) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = ?")) {
                stmt.setString(1, slotName);
                stmt.execute();
                log.info("Dropped replication slot '{}'", slotName);
            }
        }
    }
}
