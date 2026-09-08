package com.safemigrate.core.cutover;

import com.safemigrate.core.backfill.AdaptiveThrottler;
import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.preflight.ConstraintInspector;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.PostgresReplicationConnectionFactory;
import com.safemigrate.core.wal.ReplicationSlotGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enterprise Production Hardening Test Suite:
 * Validates real-world database production operational hazards:
 * 1. Sequence & Auto-Increment High-Watermark Sync (preventing duplicate key collisions post-cutover).
 * 2. Query Planner Statistics Warming via ANALYZE (preventing post-cutover CPU spikes from sequential scans).
 * 3. Dynamic Adaptive Throttling based on replication lag.
 * 4. Foreign Key incoming and outgoing dependency inspection.
 * 5. Replication Slot Health & WAL retention disk spillover guard.
 * 6. Lock Contention Diagnostics on cutover timeout.
 */
class ProductionHardeningIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductionHardeningIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String REDIS_URL = "redis://localhost:6380";

    private String testTable;
    private String shadowTable;
    private String oldTable;
    private String migrationId;

    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "orders_prod_" + runId;
        shadowTable = testTable + "__shadow";
        oldTable = testTable + "__old";
        migrationId = "mig-prod-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            try {
                stateStore.forceReleaseTableLock(testTable);
            } catch (Exception ignored) {
            }
            stateStore.close();
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS child_items CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS parent_users CASCADE;");
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void shouldSyncSequenceHighWatermarkAndPreventDuplicateKeyViolation() throws Exception {
        log.info("=== PROD TEST 1: Auto-Increment Sequence High-Watermark Sync ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, item_name VARCHAR(64));");

            // Seed historical rows with explicit IDs 1..50
            String insertSql = "INSERT INTO " + testTable + " (id, item_name) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                for (int i = 1; i <= 50; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, "item_" + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        shadowManager.createShadowTable(testTable, "ADD COLUMN sku VARCHAR(32) DEFAULT 'DEFAULT_SKU'");

        // Backfill rows 1..50 into shadow table
        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable,
                "id", List.of("id", "item_name"), 25, 0
        )) {
            worker.runBackfill();
        }

        // Execute cutover
        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, testTable, shadowTable, oldTable, 2000L
        )) {
            coordinator.executeCutover();
        }

        // Synchronize sequence high-watermark post-cutover
        SequenceManager seqManager = new SequenceManager(connection);
        long syncedWatermark = seqManager.syncSequenceHighWatermark(testTable, "id");
        assertThat(syncedWatermark).isEqualTo(50L);

        // Verify post-cutover write without ID: nextval() must generate 51 without duplicate key error!
        long generatedId;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("INSERT INTO " + testTable + " (item_name) VALUES ('new_post_cutover_item') RETURNING id")) {
            rs.next();
            generatedId = rs.getLong(1);
        }

        log.info("First post-cutover INSERT generated ID: {}", generatedId);
        assertThat(generatedId).isEqualTo(51L);
    }

    @Test
    void shouldWarmQueryPlannerStatisticsViaAnalyzeBeforeCutover() throws Exception {
        log.info("=== PROD TEST 2: Query Planner Statistics Warming via ANALYZE ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, code VARCHAR(32));");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN tags TEXT DEFAULT 'standard'");

        // Populate shadow table with 100 rows
        String insertSql = "INSERT INTO " + shadowTable + " (id, code) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            for (int i = 1; i <= 100; i++) {
                ps.setLong(1, i);
                ps.setString(2, "code_" + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        TableStatsOptimizer optimizer = new TableStatsOptimizer(connection);
        long estimatedRows = optimizer.warmPlannerStatistics(shadowTable);

        log.info("Query planner statistics for '{}': reltuples = {}", shadowTable, estimatedRows);
        assertThat(estimatedRows).isGreaterThanOrEqualTo(90L);
    }

    @Test
    void shouldDynamicallyAdjustBackfillThrottleBasedOnReplicationLag() {
        log.info("=== PROD TEST 3: Dynamic Adaptive Backfill Throttling ===");

        // Throttler with 20ms base, 500ms max, 100KB target, 2MB emergency
        AdaptiveThrottler throttler = new AdaptiveThrottler(20, 500, 100 * 1024, 2 * 1024 * 1024);

        // 1. Healthy lag (50 KB <= 100 KB target) -> base delay 20ms
        long delayHealthy = throttler.evaluateThrottle(50 * 1024);
        assertThat(delayHealthy).isEqualTo(20L);
        assertThat(throttler.isEmergencyHold(50 * 1024)).isFalse();

        // 2. Elevated lag (1 MB) -> throttles up proportionally
        long delayElevated = throttler.evaluateThrottle(1024 * 1024);
        log.info("Elevated lag (1MB) throttle delay: {} ms", delayElevated);
        assertThat(delayElevated).isGreaterThan(150L).isLessThan(500L);
        assertThat(throttler.isEmergencyHold(1024 * 1024)).isFalse();

        // 3. Critical lag (2.5 MB >= 2MB emergency limit) -> maximum throttle & emergency hold
        long delayCritical = throttler.evaluateThrottle((long) (2.5 * 1024 * 1024));
        assertThat(delayCritical).isEqualTo(500L);
        assertThat(throttler.isEmergencyHold((long) (2.5 * 1024 * 1024))).isTrue();
    }

    @Test
    void shouldInspectIncomingAndOutgoingForeignKeyDependencies() throws Exception {
        log.info("=== PROD TEST 4: Foreign Key Dependency Inspection ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE parent_users (user_id BIGINT PRIMARY KEY, username VARCHAR(64));");
            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGINT PRIMARY KEY, " +
                    "user_id BIGINT NOT NULL REFERENCES parent_users(user_id)" +
                    ");");
            stmt.execute("CREATE TABLE child_items (" +
                    "item_id BIGINT PRIMARY KEY, " +
                    "order_id BIGINT NOT NULL REFERENCES " + testTable + "(id)" +
                    ");");
        }

        ConstraintInspector inspector = new ConstraintInspector(connection);

        // Inspect incoming foreign keys (child_items -> testTable)
        List<ConstraintInspector.ForeignKeyDependency> incoming = inspector.findIncomingForeignKeys(testTable);
        assertThat(incoming).hasSize(1);
        assertThat(incoming.getFirst().childTable()).isEqualTo("child_items");
        assertThat(incoming.getFirst().childColumn()).isEqualTo("order_id");

        // Inspect outgoing foreign keys (testTable -> parent_users)
        List<ConstraintInspector.ForeignKeyDependency> outgoing = inspector.findOutgoingForeignKeys(testTable);
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.getFirst().parentTable()).isEqualTo("parent_users");
        assertThat(outgoing.getFirst().parentColumn()).isEqualTo("user_id");
    }

    @Test
    void shouldMonitorReplicationSlotHealthAndDetectRetention() throws Exception {
        log.info("=== PROD TEST 5: Replication Slot Health & Retention Monitoring ===");

        String slotName = "slot_prod_guard_" + System.currentTimeMillis();
        PostgresReplicationConnectionFactory repFactory =
                new PostgresReplicationConnectionFactory(JDBC_URL, "postgres", "password");
        repFactory.ensureReplicationSlotExists(slotName, "test_decoding");

        try {
            ReplicationSlotGuard slotGuard = new ReplicationSlotGuard(connection);
            ReplicationSlotGuard.SlotHealth health = slotGuard.inspectSlot(slotName);

            assertThat(health.exists()).isTrue();
            assertThat(health.walStatus()).isIn("normal", "reserved", null);
            assertThat(health.isHealthy(500 * 1024 * 1024L)).isTrue();
        } finally {
            try {
                repFactory.dropReplicationSlot(slotName);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void shouldDiagnoseConflictingLockHoldersWhenCutoverTimesOut() throws Exception {
        log.info("=== PROD TEST 6: Lock Blocker Diagnostics on Timeout ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, note TEXT);");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN col INT DEFAULT 0");

        Connection blockerConn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        blockerConn.setAutoCommit(false);

        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch testFinishedLatch = new CountDownLatch(1);

        Thread blockerThread = new Thread(() -> {
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.execute("LOCK TABLE " + testTable + " IN ACCESS EXCLUSIVE MODE;");
                lockAcquiredLatch.countDown();
                testFinishedLatch.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            } finally {
                try {
                    blockerConn.rollback();
                    blockerConn.close();
                } catch (Exception ignored) {
                }
            }
        });
        blockerThread.start();

        assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Run cutover with strict 400ms timeout; blocker holds lock so cutover will timeout and run diagnostics
        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, testTable, shadowTable, oldTable, 400L
        )) {
            assertThatThrownBy(coordinator::executeCutover)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("lock timeout");

            // Verify state reverted safely to CATCHING_UP
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);
        } finally {
            testFinishedLatch.countDown();
            blockerThread.join(2000);
        }
    }
}
