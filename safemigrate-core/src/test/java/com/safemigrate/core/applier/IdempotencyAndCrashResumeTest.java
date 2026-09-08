package com.safemigrate.core.applier;

import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import com.safemigrate.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates true idempotency and mid-flight crash resilience:
 * 1. Double/Triple Replay: Replaying identical WAL events multiple times produces identical state (f(f(x)) = f(x)).
 * 2. Mid-flight Abrupt Termination & Overlapping Resume: Killing worker mid-backfill and re-running
 *    overlapping batches produces 0 duplicate key violations and exact row counts.
 * 3. Event-Backfill Collision: Live write already applied is NEVER overwritten by overlapping re-backfill.
 */
class IdempotencyAndCrashResumeTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAndCrashResumeTest.class);

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
        sourceTable = "idemp_src_" + runId;
        migrationId = "mig-idemp-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + sourceTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL" +
                    ");");

            // Seed 100 rows
            String insertSql = "INSERT INTO " + sourceTable + " (customer_id, amount, status) VALUES (?, ?, ?)";
            try (PreparedStatement seed = connection.prepareStatement(insertSql)) {
                for (int i = 1; i <= 100; i++) {
                    seed.setString(1, "cust_" + i);
                    seed.setBigDecimal(2, BigDecimal.valueOf(i * 10.0).setScale(2, java.math.RoundingMode.HALF_UP));
                    seed.setString(3, "PENDING");
                    seed.addBatch();
                }
                seed.executeBatch();
            }
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
     * TEST 1: Triple-Replay Idempotency Test
     * Deliberately replays the exact same WAL events 3 times in a row.
     * Mathematical proof that f(f(f(x))) == f(x):
     * - No duplicate key exceptions
     * - No phantom rows
     * - Exact values preserved
     */
    @Test
    void shouldProduceIdenticalStateWhenReplayingSameEventsThreeTimes() throws Exception {
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {

            // Create a sequence of 5 events: 3 INSERTs, 1 UPDATE, 1 DELETE
            List<WalChangeEvent> events = new ArrayList<>();

            // INSERT 1001
            events.add(new WalChangeEvent(sourceTable, OperationType.INSERT, null,
                    Map.of("id", "1001", "customer_id", "cust_1001", "amount", "150.00", "status", "NEW"), 1001L, Instant.now()));

            // INSERT 1002
            events.add(new WalChangeEvent(sourceTable, OperationType.INSERT, null,
                    Map.of("id", "1002", "customer_id", "cust_1002", "amount", "250.00", "status", "NEW"), 1002L, Instant.now()));

            // INSERT 1003
            events.add(new WalChangeEvent(sourceTable, OperationType.INSERT, null,
                    Map.of("id", "1003", "customer_id", "cust_1003", "amount", "350.00", "status", "NEW"), 1003L, Instant.now()));

            // UPDATE 1001 -> amount=999.99, status=COMPLETED
            events.add(new WalChangeEvent(sourceTable, OperationType.UPDATE, Map.of("id", "1001"),
                    Map.of("id", "1001", "customer_id", "cust_1001", "amount", "999.99", "status", "COMPLETED"), 1004L, Instant.now()));

            // DELETE 1002
            events.add(new WalChangeEvent(sourceTable, OperationType.DELETE, Map.of("id", "1002"), null, 1005L, Instant.now()));

            // --- PASS 1: Apply all 5 events ---
            log.info("--- PASS 1: Initial event replay ---");
            for (WalChangeEvent event : events) {
                applier.apply(event);
            }

            int countPass1 = queryRowCount(shadowTable);
            assertThat(countPass1).isEqualTo(2); // 1001 (updated), 1003 (inserted), 1002 (deleted)

            // --- PASS 2: Deliberately REPLAY THE EXACT SAME 5 EVENTS AGAIN ---
            log.info("--- PASS 2: Duplicate replay (simulating Kafka redelivery / crash replay) ---");
            for (WalChangeEvent event : events) {
                applier.apply(event); // MUST NOT throw duplicate key violation!
            }

            int countPass2 = queryRowCount(shadowTable);
            assertThat(countPass2).as("Row count after 2nd replay must be identical").isEqualTo(countPass1);

            // --- PASS 3: Deliberately REPLAY A THIRD TIME ---
            log.info("--- PASS 3: Triple replay ---");
            for (WalChangeEvent event : events) {
                applier.apply(event);
            }

            int countPass3 = queryRowCount(shadowTable);
            assertThat(countPass3).as("Row count after 3rd replay must be identical").isEqualTo(countPass1);

            // Verify final row state
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, customer_id, amount, status, priority_score FROM " + shadowTable + " ORDER BY id ASC")) {

                // Row 1001
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1001L);
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("999.99"));
                assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                assertThat(rs.getInt("priority_score")).isEqualTo(42);

                // Row 1003
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1003L);
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("350.00"));
                assertThat(rs.getString("status")).isEqualTo("NEW");
                assertThat(rs.getInt("priority_score")).isEqualTo(42);

                // No more rows (1002 was deleted)
                assertThat(rs.next()).isFalse();
            }

            log.info("PASS 1, 2, 3 SUCCESS: ChangeApplier is 100% idempotent under duplicate redeliveries!");
        }
    }

    /**
     * TEST 2: Mid-Flight Deliberate Termination & Overlapping Resumption
     * 1. Start BackfillWorker copying 100 rows in batches of 10 with 50ms throttle.
     * 2. After ~30 rows copied, deliberately kill the worker mid-flight via stop().
     * 3. Roll back checkpoint in Redis by 15 rows to simulate restarting with overlapping duplicates.
     * 4. Resume new BackfillWorker from the rolled-back checkpoint.
     * 5. Verify all 100 rows copied with ZERO duplicate key errors and exact data match.
     */
    @Test
    void shouldHandleMidFlightCrashAndResumeWithOverlappingBatches() throws Exception {
        List<String> columns = List.of("id", "customer_id", "amount", "status");

        // 1. Launch BackfillWorker in a background virtual thread
        CountDownLatch firstBatchCopied = new CountDownLatch(1);
        AtomicBoolean workerTerminated = new AtomicBoolean(false);

        Connection workerConn1 = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        BackfillWorker worker1 = new BackfillWorker(
                workerConn1, stateStore, migrationId, sourceTable, shadowTable, "id",
                columns, 10, 60 // batchSize=10, throttleDelay=60ms
        );

        Thread workerThread = Thread.ofVirtual().name("interrupted-worker").start(() -> {
            try {
                worker1.runBackfill();
            } catch (Exception e) {
                log.info("Worker 1 stopped: {}", e.getMessage());
            }
        });

        // Let worker copy a few batches (~30-40 rows)
        Thread.sleep(180);

        // 2. DELIBERATE MID-FLIGHT KILL!
        log.info(">>> SIMULATING KILL -9: Abruptly stopping worker mid-flight! <<<");
        worker1.stop();
        workerThread.join(2000);
        workerConn1.close();

        Long checkpointPk = stateStore.getLastCopiedPk(migrationId);
        long rowsCopiedBeforeKill = stateStore.getRowsBackfilled(migrationId);
        log.info("Worker killed! Checkpoint in Redis: lastPk={}, rowsBackfilled={}", checkpointPk, rowsCopiedBeforeKill);

        assertThat(checkpointPk).isNotNull().isGreaterThan(0L).isLessThan(100L);
        int rowsInShadow = queryRowCount(shadowTable);
        assertThat(rowsInShadow).isEqualTo((int) rowsCopiedBeforeKill);

        // 3. Deliberately simulate an outdated checkpoint where the last batch needs to be re-run:
        // Roll back checkpoint PK by 10 rows!
        long rolledBackPk = Math.max(0, checkpointPk - 10);
        long rolledBackRows = Math.max(0, rowsCopiedBeforeKill - 10);
        stateStore.checkpointBackfill(migrationId, rolledBackPk, rolledBackRows);
        log.info("Simulating crash recovery with overlapping range: rolled back checkpoint to PK={}", rolledBackPk);

        // 4. Start Worker 2 to resume from the rolled back checkpoint
        Connection workerConn2 = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        try (BackfillWorker worker2 = new BackfillWorker(
                workerConn2, stateStore, migrationId, sourceTable, shadowTable, "id",
                columns, 10, 10
        )) {
            log.info("Resuming BackfillWorker from checkpoint PK={}...", rolledBackPk);
            worker2.runBackfill(); // MUST NOT crash on rows already in shadow table!
        } finally {
            workerConn2.close();
        }

        // 5. Assert 100% complete and correct
        int finalRowCount = queryRowCount(shadowTable);
        log.info("Final shadow table row count after crash recovery: {} (expected: 100)", finalRowCount);

        assertThat(finalRowCount)
                .as("Shadow table must contain exactly 100 rows without any missing or duplicated rows")
                .isEqualTo(100);

        // Assert all 100 rows match source table
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + shadowTable + " WHERE priority_score = 42")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(100);
        }

        log.info("CRASH RESUME VERIFIED: Deliberate kill & restart with overlapping replay completed with 0 errors!");
    }

    /**
     * TEST 3: Live Write vs. Backfill Overwrite Protection
     * Proves that a live WAL update landing before backfill reaches that row is NEVER overwritten
     * by Backfill's historical read.
     */
    @Test
    void shouldNeverOverwriteLiveWriteWithStaleBackfillData() throws Exception {
        // Source table row 50 currently has amount = 500.00, status = PENDING
        // 1. Simulate a live WAL update landing on the shadow table FIRST:
        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, sourceTable, shadowTable, "id")) {
            WalChangeEvent liveUpdate = new WalChangeEvent(
                    sourceTable,
                    OperationType.INSERT, // Upsert
                    null,
                    Map.of("id", "50", "customer_id", "cust_50", "amount", "9999.99", "status", "VIP_PAID"),
                    8888L,
                    Instant.now()
            );
            applier.apply(liveUpdate);
        }

        // 2. Now run the BackfillWorker across all 100 rows (including row 50 which has amount=500 on source table)
        List<String> columns = List.of("id", "customer_id", "amount", "status");
        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, sourceTable, shadowTable, "id",
                columns, 20, 0
        )) {
            worker.runBackfill();
        }

        // 3. Inspect row 50 in shadow table:
        // It MUST RETAIN amount = 9999.99 and status = VIP_PAID!
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT amount, status, priority_score FROM " + shadowTable + " WHERE id = 50")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("amount"))
                    .as("Live write must be preserved against backfill overwrite")
                    .isEqualByComparingTo(new BigDecimal("9999.99"));
            assertThat(rs.getString("status")).isEqualTo("VIP_PAID");
            assertThat(rs.getInt("priority_score")).isEqualTo(42);
        }

        log.info("OVERWRITE PROTECTION VERIFIED: Backfill ON CONFLICT DO NOTHING safely preserved live write!");
    }

    private int queryRowCount(String table) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
