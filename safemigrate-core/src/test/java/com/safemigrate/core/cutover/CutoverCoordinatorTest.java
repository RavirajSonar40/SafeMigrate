package com.safemigrate.core.cutover;

import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CutoverCoordinatorTest {

    private static final Logger log = LoggerFactory.getLogger(CutoverCoordinatorTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String USER = "postgres";
    private static final String PASSWORD = "password";
    private static final String REDIS_URL = "redis://localhost:6380";

    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;
    private String sourceTable;
    private String shadowTable;
    private String oldTable;
    private String migrationId;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        sourceTable = "cutover_src_" + runId;
        shadowTable = sourceTable + "__shadow";
        oldTable = sourceTable + "__old";
        migrationId = "mig-cutover-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        // 1. Create source table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");

            stmt.execute("CREATE TABLE " + sourceTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL" +
                    ");");

            // Seed 100 rows into source table
            for (int i = 1; i <= 100; i++) {
                stmt.execute(String.format(
                        "INSERT INTO %s (customer_id, amount) VALUES ('cust_%d', %d.50);",
                        sourceTable, i, i * 10));
            }
        }

        // 2. Provision shadow table with new column priority_score INT DEFAULT 42
        shadowManager.createShadowTable(sourceTable, "ADD COLUMN priority_score INT DEFAULT 42");

        // Seed 100 rows into shadow table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(String.format(
                    "INSERT INTO %s (id, customer_id, amount, priority_score) " +
                            "SELECT id, customer_id, amount, 42 FROM %s;",
                    shadowTable, sourceTable));
        }

        stateStore.setStatus(migrationId, MigrationState.CATCHING_UP);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
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
     * TEST 1: Atomic Table Swap in single-digit milliseconds.
     */
    @Test
    void shouldExecuteAtomicCutoverInMilliseconds() throws Exception {
        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, sourceTable, shadowTable, oldTable, 2000L)) {

            long durationMs = coordinator.executeCutover();
            log.info("Atomic cutover completed in {} ms!", durationMs);

            assertThat(durationMs)
                    .as("Table rename is metadata-only and must execute in under 100ms")
                    .isLessThan(100L);

            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.COMPLETED);

            // Verify sourceTable now HAS the new column priority_score
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, customer_id, amount, priority_score FROM " + sourceTable + " WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("priority_score")).isEqualTo(42);
            }

            // Verify oldTable exists and DOES NOT have priority_score
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '" + oldTable + "' AND column_name = 'priority_score'")) {
                assertThat(rs.next()).as("oldTable must not contain new column").isFalse();
            }

            // Verify row counts
            try (Statement stmt = connection.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + sourceTable)) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(100);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + oldTable)) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(100);
                }
            }
        }
    }

    /**
     * TEST 2: Pre-Cutover Rollback.
     * Drops the shadow table and transitions to ROLLED_BACK. The source table is untouched.
     */
    @Test
    void shouldSafelyRollbackBeforeCutover() throws Exception {
        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, sourceTable, shadowTable, oldTable, 2000L)) {

            coordinator.rollback();

            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.ROLLED_BACK);

            // Shadow table must be dropped
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT to_regclass('" + shadowTable + "');")) {
                rs.next();
                assertThat(rs.getString(1)).isNull();
            }

            // Source table must remain 100% intact
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + sourceTable)) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(100);
            }
        }
    }

    /**
     * TEST 3: Emergency Reversion After Cutover.
     * Swaps the old table back to production if a post-cutover application bug is found.
     */
    @Test
    void shouldEmergencyRevertAfterCutover() throws Exception {
        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, sourceTable, shadowTable, oldTable, 2000L)) {

            // 1. Promote to new schema
            coordinator.executeCutover();
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.COMPLETED);

            // Verify promoted schema has priority_score
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '" + sourceTable + "' AND column_name = 'priority_score'")) {
                assertThat(rs.next()).isTrue();
            }

            // 2. Emergency revert back to old schema
            long revertDurationMs = coordinator.emergencyRevert();
            log.info("Emergency reversion completed in {} ms!", revertDurationMs);

            assertThat(revertDurationMs).isLessThan(100L);
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.ROLLED_BACK);

            // 3. Assert sourceTable is restored to old schema (NO priority_score)
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '" + sourceTable + "' AND column_name = 'priority_score'")) {
                assertThat(rs.next()).as("Reverted source table must no longer have new column").isFalse();
            }

            // 4. Assert all 100 rows remain intact
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + sourceTable)) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(100);
            }
        }
    }

    /**
     * TEST 4: Lock Timeout Protection.
     * Proves that when another query holds an exclusive lock, the cutover aborts immediately
     * after lockTimeoutMs without hanging or starving traffic.
     */
    @Test
    void shouldAbortCutoverGracefullyWhenLockTimeoutExceeded() throws Exception {
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        AtomicBoolean blockingThreadError = new AtomicBoolean(false);

        // Thread 2: Simulates a long-running transaction holding an exclusive lock on sourceTable
        Thread blockerThread = Thread.ofVirtual().start(() -> {
            try (Connection blockerConn = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
                blockerConn.setAutoCommit(false);
                try (Statement stmt = blockerConn.createStatement()) {
                    stmt.execute("LOCK TABLE " + sourceTable + " IN ACCESS EXCLUSIVE MODE;");
                    log.info("Blocker thread acquired ACCESS EXCLUSIVE lock on '{}'", sourceTable);
                    lockAcquiredLatch.countDown();

                    // Hold the lock until instructed to release
                    releaseLockLatch.await(5, TimeUnit.SECONDS);
                }
                blockerConn.rollback();
                log.info("Blocker thread released lock.");
            } catch (Exception e) {
                blockingThreadError.set(true);
                lockAcquiredLatch.countDown();
            }
        });

        // Wait for blocker thread to lock the table
        assertThat(lockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(blockingThreadError.get()).isFalse();

        // Main thread: Attempt cutover with 300ms lock timeout
        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, sourceTable, shadowTable, oldTable, 300L)) {

            // Must throw SQLException due to lock timeout
            assertThatThrownBy(coordinator::executeCutover)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("lock timeout");

            // State should revert to CATCHING_UP to retry later
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);

            // Tables must remain un-swapped!
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT to_regclass('" + shadowTable + "');")) {
                rs.next();
                assertThat(rs.getString(1)).isNotNull(); // shadowTable still exists
            }
        } finally {
            // Signal blocker to release lock
            releaseLockLatch.countDown();
            blockerThread.join(2000);
        }
    }
}
