package com.safemigrate.core.applier;

import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import com.safemigrate.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeApplierTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String USER = "postgres";
    private static final String PASSWORD = "password";
    private static final String REDIS_URL = "redis://localhost:6380";

    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowTableManager;
    private String sourceTable;
    private String shadowTable;
    private String migrationId;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        stateStore = new StateStore(REDIS_URL);
        shadowTableManager = new ShadowTableManager(connection);

        long runId = System.currentTimeMillis();
        sourceTable = "test_src_" + runId;
        migrationId = "mig-" + runId;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + sourceTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL" +
                    ");");
        }

        // Provision shadow table with a new column that has a default value
        shadowTable = shadowTableManager.createShadowTable(sourceTable, "ADD COLUMN priority_score INT DEFAULT 42");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void shouldReplayInsertUpdateAndDeleteWithPostgresCasting() throws Exception {
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {

            // 1. Replay an INSERT event
            Map<String, Object> insertValues = new HashMap<>();
            insertValues.put("id", "101");
            insertValues.put("customer_id", "cust_live_1");
            insertValues.put("amount", "99.50");
            insertValues.put("status", "PENDING");

            WalChangeEvent insertEvent = new WalChangeEvent(
                    sourceTable,
                    OperationType.INSERT,
                    null,
                    insertValues,
                    5001L,
                    Instant.now()
            );

            applier.apply(insertEvent);

            assertThat(applier.getInsertCount()).isEqualTo(1);
            assertThat(applier.getTotalApplied()).isEqualTo(1);
            assertThat(stateStore.getLastAppliedLsn(migrationId)).isEqualTo(5001L);

            // Verify row in shadow table and verify extra column has default value 42
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, customer_id, amount, status, priority_score FROM " + shadowTable + " WHERE id = 101")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(101L);
                assertThat(rs.getString("customer_id")).isEqualTo("cust_live_1");
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("99.50"));
                assertThat(rs.getString("status")).isEqualTo("PENDING");
                assertThat(rs.getInt("priority_score")).isEqualTo(42);
            }

            // 2. Replay an UPDATE event
            Map<String, Object> updateValues = new HashMap<>();
            updateValues.put("id", "101");
            updateValues.put("customer_id", "cust_live_1");
            updateValues.put("amount", "149.99");
            updateValues.put("status", "COMPLETED");

            WalChangeEvent updateEvent = new WalChangeEvent(
                    sourceTable,
                    OperationType.UPDATE,
                    insertValues,
                    updateValues,
                    5002L,
                    Instant.now()
            );

            applier.apply(updateEvent);

            assertThat(applier.getUpdateCount()).isEqualTo(1);
            assertThat(applier.getTotalApplied()).isEqualTo(2);
            assertThat(stateStore.getLastAppliedLsn(migrationId)).isEqualTo(5002L);

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT amount, status, priority_score FROM " + shadowTable + " WHERE id = 101")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("149.99"));
                assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                assertThat(rs.getInt("priority_score")).isEqualTo(42); // Unchanged!
            }

            // 3. Replay a DELETE event
            Map<String, Object> oldKey = Map.of("id", "101");
            WalChangeEvent deleteEvent = new WalChangeEvent(
                    sourceTable,
                    OperationType.DELETE,
                    oldKey,
                    null,
                    5003L,
                    Instant.now()
            );

            applier.apply(deleteEvent);

            assertThat(applier.getDeleteCount()).isEqualTo(1);
            assertThat(applier.getTotalApplied()).isEqualTo(3);
            assertThat(stateStore.getLastAppliedLsn(migrationId)).isEqualTo(5003L);

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + shadowTable + " WHERE id = 101")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void shouldHandleUpsertFallbackForUpdateWhenRowNotYetInShadowTable() throws Exception {
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {

            // Row 200 does not exist in shadow table yet (simulating live update before backfill reaches it)
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("id", "200");
            newValues.put("customer_id", "cust_early_update");
            newValues.put("amount", "250.00");
            newValues.put("status", "PROCESSING");

            WalChangeEvent updateEvent = new WalChangeEvent(
                    sourceTable,
                    OperationType.UPDATE,
                    Map.of("id", "200"),
                    newValues,
                    6001L,
                    Instant.now()
            );

            applier.apply(updateEvent);

            // Should have inserted row 200 via fallback UPSERT
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, customer_id, amount, status, priority_score FROM " + shadowTable + " WHERE id = 200")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(200L);
                assertThat(rs.getString("customer_id")).isEqualTo("cust_early_update");
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("250.00"));
                assertThat(rs.getString("status")).isEqualTo("PROCESSING");
                assertThat(rs.getInt("priority_score")).isEqualTo(42);
            }
        }
    }
}
