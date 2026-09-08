package com.safemigrate.core.applier;

import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deep-dive audit tests covering systems-level edge cases:
 * 1. NULL Values & Column Clearing via UPDATE
 * 2. Empty Table Migration (0 rows)
 * 3. Highly Sparse Primary Keys with Large Gaps
 * 4. Idempotent Repeated & Non-Existent DELETEs
 * 5. Distributed Lock Mutual Exclusion (StateStore)
 * 6. Monotonic LSN Checkpointing (ignoring out-of-order stale LSNs)
 */
class EdgeCasesAndIdempotencyAuditTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String USER = "postgres";
    private static final String PASSWORD = "password";
    private static final String REDIS_URL = "redis://localhost:6380";

    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;
    private String sourceTable;
    private String shadowTable;
    private String migrationId;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        sourceTable = "audit_src_" + runId;
        migrationId = "mig-audit-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + sourceTable + " (" +
                    "id BIGINT PRIMARY KEY, " +
                    "customer_id VARCHAR(64), " +
                    "amount NUMERIC(10, 2), " +
                    "status VARCHAR(32)" +
                    ");");
        }

        shadowTable = shadowManager.createShadowTable(sourceTable, "ADD COLUMN priority_score INT DEFAULT 42");
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

    /**
     * AUDIT 1: NULL value handling and explicitly clearing a column to NULL via UPDATE.
     */
    @Test
    void shouldHandleNullValuesAndClearingColumnsToNull() throws Exception {
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {

            // Step 1: Insert row with full values
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("id", "1");
            newValues.put("customer_id", "cust_active");
            newValues.put("amount", "100.50");
            newValues.put("status", "ACTIVE");

            applier.apply(new WalChangeEvent(sourceTable, OperationType.INSERT, null, newValues, 101L, Instant.now()));

            // Verify row inserted
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT customer_id, amount, status FROM " + shadowTable + " WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                assertThat(rs.getString("customer_id")).isEqualTo("cust_active");
            }

            // Step 2: UPDATE event setting status and customer_id to NULL
            Map<String, Object> updateValues = new HashMap<>();
            updateValues.put("id", "1");
            updateValues.put("customer_id", null);
            updateValues.put("amount", "100.50");
            updateValues.put("status", null);

            applier.apply(new WalChangeEvent(sourceTable, OperationType.UPDATE, newValues, updateValues, 102L, Instant.now()));

            // Step 3: Assert that columns were properly updated to SQL NULL in PostgreSQL
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT customer_id, amount, status, priority_score FROM " + shadowTable + " WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("customer_id")).isNull();
                assertThat(rs.getString("status")).isNull();
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("100.50"));
                assertThat(rs.getInt("priority_score")).isEqualTo(42);
            }
        }
    }

    /**
     * AUDIT 2: Empty Table Backfill (0 historical rows).
     * Must complete immediately, set status to CATCHING_UP, and produce 0 errors.
     */
    @Test
    void shouldHandleEmptySourceTableCleanly() throws Exception {
        List<String> columns = List.of("id", "customer_id", "amount", "status");

        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, sourceTable, shadowTable, "id",
                columns, 50, 0
        )) {
            worker.runBackfill();
        }

        assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);
        assertThat(stateStore.getRowsBackfilled(migrationId)).isZero();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + shadowTable)) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    /**
     * AUDIT 3: Highly sparse primary keys with large gaps (e.g. 1, 500, 999999).
     * Paged B-Tree scan must not infinite-loop or skip sparse records.
     */
    @Test
    void shouldHandleSparsePrimaryKeysWithHugeGaps() throws Exception {
        long[] sparseIds = {1L, 17L, 5000L, 999999L, 88888888L};

        String insertSql = "INSERT INTO " + sourceTable + " (id, customer_id, amount, status) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            for (long id : sparseIds) {
                stmt.setLong(1, id);
                stmt.setString(2, "cust_" + id);
                stmt.setBigDecimal(3, BigDecimal.valueOf((id % 1000) * 1.5 + 1.0).setScale(2, java.math.RoundingMode.HALF_UP));
                stmt.setString(4, "SPARSE");
                stmt.addBatch();
            }
            stmt.executeBatch();
        }

        List<String> columns = List.of("id", "customer_id", "amount", "status");

        // Small batch size of 2 to force multiple pagination hops across huge gaps
        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, sourceTable, shadowTable, "id",
                columns, 2, 5
        )) {
            worker.runBackfill();
        }

        assertThat(stateStore.getRowsBackfilled(migrationId)).isEqualTo(sparseIds.length);
        assertThat(stateStore.getLastCopiedPk(migrationId)).isEqualTo(88888888L);

        // Verify all sparse IDs exist in shadow table
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM " + shadowTable + " ORDER BY id ASC")) {
            for (long expectedId : sparseIds) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(expectedId);
            }
            assertThat(rs.next()).isFalse();
        }
    }

    /**
     * AUDIT 4: Idempotent DELETEs.
     * Deleting a non-existent row, or deleting an already-deleted row multiple times, must never error.
     */
    @Test
    void shouldIdempotentlyHandleRepeatedAndNonExistentDeletes() throws Exception {
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {

            // 1. Delete row 999999 which NEVER existed
            WalChangeEvent deleteGhost = new WalChangeEvent(
                    sourceTable, OperationType.DELETE, Map.of("id", "999999"), null, 201L, Instant.now()
            );
            applier.apply(deleteGhost); // Must NOT throw exception

            // 2. Insert row 10
            applier.apply(new WalChangeEvent(
                    sourceTable, OperationType.INSERT, null,
                    Map.of("id", "10", "customer_id", "cust_10", "amount", "10.00", "status", "OK"),
                    202L, Instant.now()
            ));

            // 3. Delete row 10 (pass 1: deleted)
            WalChangeEvent deleteRow10 = new WalChangeEvent(
                    sourceTable, OperationType.DELETE, Map.of("id", "10"), null, 203L, Instant.now()
            );
            applier.apply(deleteRow10);

            // 4. Delete row 10 AGAIN (pass 2: already deleted)
            applier.apply(deleteRow10);

            // 5. Delete row 10 a THIRD time (pass 3)
            applier.apply(deleteRow10);

            assertThat(applier.getDeleteCount()).isEqualTo(4);

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + shadowTable)) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    /**
     * AUDIT 5: Distributed Lock Mutual Exclusion (Redisson).
     * Two workers attempting to migrate the same table simultaneously must be blocked.
     */
    @Test
    void shouldEnforceDistributedLockMutualExclusion() throws Exception {
        // Worker 1 acquires lock
        RLock lock1 = stateStore.acquireTableLock(sourceTable, 2, 30);
        assertThat(lock1.isHeldByCurrentThread()).isTrue();

        // Worker 2 attempts to acquire lock with 0 wait time on another StateStore instance
        try (StateStore stateStoreWorker2 = new StateStore(REDIS_URL)) {
            assertThatThrownBy(() -> stateStoreWorker2.acquireTableLock(sourceTable, 0, 30))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Another migration is currently active");
        }

        // Worker 1 releases lock
        stateStore.releaseTableLock(lock1);
        assertThat(lock1.isHeldByCurrentThread()).isFalse();

        // Worker 2 can now acquire lock cleanly
        try (StateStore stateStoreWorker2 = new StateStore(REDIS_URL)) {
            RLock lock2 = stateStoreWorker2.acquireTableLock(sourceTable, 2, 30);
            assertThat(lock2.isHeldByCurrentThread()).isTrue();
            stateStoreWorker2.releaseTableLock(lock2);
        }
    }

    /**
     * AUDIT 6: Monotonic LSN Checkpointing.
     * Older or replayed LSNs must never downgrade the checkpoint in Redis.
     */
    @Test
    void shouldEnforceMonotonicLsnAdvancement() throws Exception {
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {

            // Event 1 at LSN 500
            applier.apply(new WalChangeEvent(
                    sourceTable, OperationType.INSERT, null,
                    Map.of("id", "1", "customer_id", "c1", "amount", "10", "status", "A"),
                    500L, Instant.now()
            ));
            assertThat(stateStore.getLastAppliedLsn(migrationId)).isEqualTo(500L);

            // Event 2 at LSN 1000
            applier.apply(new WalChangeEvent(
                    sourceTable, OperationType.UPDATE, Map.of("id", "1"),
                    Map.of("id", "1", "customer_id", "c1", "amount", "20", "status", "A"),
                    1000L, Instant.now()
            ));
            assertThat(stateStore.getLastAppliedLsn(migrationId)).isEqualTo(1000L);

            // Event 3 at LSN 700 (stale / out-of-order replay)
            applier.apply(new WalChangeEvent(
                    sourceTable, OperationType.UPDATE, Map.of("id", "1"),
                    Map.of("id", "1", "customer_id", "c1", "amount", "30", "status", "A"),
                    700L, Instant.now()
            ));

            // Must RETAIN 1000L!
            assertThat(stateStore.getLastAppliedLsn(migrationId))
                    .as("Checkpoint LSN must never regress when an older LSN is replayed")
                    .isEqualTo(1000L);
            assertThat(applier.getLastAppliedLsn()).isEqualTo(1000L);
        }
    }
}
