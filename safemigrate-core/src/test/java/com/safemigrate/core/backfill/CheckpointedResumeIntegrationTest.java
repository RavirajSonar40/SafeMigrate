package com.safemigrate.core.backfill;

import com.safemigrate.core.reconcile.DataReconciliationService;
import com.safemigrate.core.reconcile.ReconciliationReport;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Checkpointed Resume & Crash Recovery mid-backfill:
 * 1. Simulates worker pause/crash after partial backfill execution
 * 2. Verifies Redis checkpoint integrity (lastCopiedPk and rowsBackfilled)
 * 3. Spawns a new BackfillWorker instance resuming from the exact primary key
 * 4. Verifies zero duplicated writes, complete historical backfill, and 100% data parity
 */
class CheckpointedResumeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CheckpointedResumeIntegrationTest.class);
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String REDIS_URL = "redis://localhost:6380";

    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;
    private String testTable;
    private String shadowTable;
    private String migrationId;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "resume_test_" + runId;
        shadowTable = testTable + "__shadow";
        migrationId = "mig-resume-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");

            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGINT PRIMARY KEY, " +
                    "item_sku VARCHAR(32) NOT NULL, " +
                    "quantity INT NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ");");

            // Seed 300 rows (PKs 1 to 300)
            String sql = "INSERT INTO " + testTable + " (id, item_sku, quantity) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 1; i <= 300; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, "SKU_" + i);
                    ps.setInt(3, i * 2);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        // Provision empty shadow table with DDL addition
        shadowManager.createShadowTable(testTable, "ADD COLUMN warehouse_id INT DEFAULT 99");
    }

    @AfterEach
    void tearDown() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
        } catch (Exception ignored) {}

        try {
            if (stateStore != null) stateStore.close();
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (Exception ignored) {}
    }

    @Test
    void testPauseAndResumeFromExactCheckpointedPk() throws Exception {
        List<String> cols = shadowManager.getColumnNames(testTable);

        // 1. Start initial BackfillWorker with batchSize = 25 and pause after 3 batches (75 rows)
        BackfillWorker worker1 = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable, "id", cols, 25, 20
        );

        CountDownLatch pauseLatch = new CountDownLatch(3);
        worker1.setProgressListener((lastPk, totalCopied) -> {
            log.info("Worker 1 progress: lastPk={}, totalCopied={}", lastPk, totalCopied);
            pauseLatch.countDown();
            if (pauseLatch.getCount() == 0) {
                log.info("Pausing Worker 1 mid-flight at PK {}...", lastPk);
                worker1.stop();
                stateStore.setStatus(migrationId, MigrationState.PAUSED);
            }
        });

        // Run worker1 in background thread
        Thread workerThread1 = new Thread(() -> {
            try {
                worker1.runBackfill();
            } catch (Exception e) {
                log.error("Worker 1 failed: {}", e.getMessage(), e);
            }
        });
        workerThread1.start();

        // Wait until paused
        assertThat(pauseLatch.await(5, TimeUnit.SECONDS)).isTrue();
        workerThread1.join(3000);

        // 2. Verify state is PAUSED and checkpoint matches exactly
        assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.PAUSED);
        Long checkpointPk = stateStore.getLastCopiedPk(migrationId);
        long rowsCopiedPhase1 = stateStore.getRowsBackfilled(migrationId);

        log.info("Migration [{}] PAUSED at checkpointPk={}, rowsBackfilled={}",
                migrationId, checkpointPk, rowsCopiedPhase1);

        assertThat(checkpointPk).isEqualTo(75L);
        assertThat(rowsCopiedPhase1).isEqualTo(75L);

        // Verify shadow table currently has exactly 75 rows
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(75L);
        }

        // 3. Resume Migration: simulate new worker resuming from checkpoint
        stateStore.setStatus(migrationId, MigrationState.RESUMING);
        BackfillWorker worker2 = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable, "id", cols, 50, 0
        );

        AtomicBoolean resumedFromCheckpoint = new AtomicBoolean(false);
        worker2.setProgressListener((lastPk, totalCopied) -> {
            log.info("Worker 2 progress: lastPk={}, totalCopied={}", lastPk, totalCopied);
            if (totalCopied > 75L) {
                resumedFromCheckpoint.set(true);
            }
        });

        log.info("Resuming backfill with Worker 2 from checkpoint...");
        worker2.runBackfill();

        // 4. Verify Worker 2 completed full backfill
        assertThat(resumedFromCheckpoint.get()).isTrue();
        assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);
        assertThat(stateStore.getRowsBackfilled(migrationId)).isEqualTo(300L);
        assertThat(stateStore.getLastCopiedPk(migrationId)).isEqualTo(300L);

        // Verify shadow table has all 300 rows
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(300L);
        }

        // 5. Verify mathematical reconciliation parity
        DataReconciliationService recon = new DataReconciliationService(connection);
        ReconciliationReport report = recon.reconcile(shadowTable, testTable);
        log.info("Reconciliation after resume: matched={}, checksum={}", report.isMatched(), report.getSourceChecksum());

        assertThat(report.isMatched()).isTrue();
        assertThat(report.getSourceRowCount()).isEqualTo(300L);
        assertThat(report.getTargetRowCount()).isEqualTo(300L);
        assertThat(report.getDiscrepancyCount()).isEqualTo(0);
    }
}
