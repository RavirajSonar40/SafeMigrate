package com.safemigrate.core.backfill;

import com.safemigrate.core.applier.ChangeApplier;
import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.kafka.WalKafkaConsumer;
import com.safemigrate.core.kafka.WalKafkaProducer;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.PostgresReplicationConnectionFactory;
import com.safemigrate.core.wal.TestDecodingDecoder;
import com.safemigrate.core.wal.WalReader;
import com.safemigrate.loadgen.LoadGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 Verification Test Suite:
 * Proves crash-safe checkpointing and worker resilience under process kills (kill -9),
 * distributed lock lease expiration, and concurrent live-write convergence after worker death.
 */
class WorkerResilienceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WorkerResilienceIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";
    private static final String REDIS_URL = "redis://localhost:6380";

    private String testTable;
    private String shadowTable;
    private String migrationId;

    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;
    private LoadGenerator loadGen;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "orders_resilience_" + runId;
        shadowTable = testTable + "__shadow";
        migrationId = "mig-resilience-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (loadGen != null) {
            try {
                loadGen.stop();
            } catch (Exception ignored) {
            }
        }
        if (stateStore != null) {
            try {
                stateStore.forceReleaseTableLock(testTable);
            } catch (Exception ignored) {
            }
            stateStore.close();
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private void seedRows(int count) throws Exception {
        String insertSql = "INSERT INTO " + testTable + " (customer_id, amount, status) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            for (int i = 1; i <= count; i++) {
                stmt.setString(1, "cust_" + i);
                stmt.setBigDecimal(2, BigDecimal.valueOf(i * 5.0).setScale(2, java.math.RoundingMode.HALF_UP));
                stmt.setString(3, "PENDING");
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    @Test
    void shouldSurviveProcessKillAndResumeFromRedisCheckpoint() throws Exception {
        log.info("=== TEST 1: Process Kill (kill -9) Simulation and Redis Checkpoint Recovery ===");
        int totalRows = 500;
        seedRows(totalRows);

        // Provision shadow table
        shadowManager.createShadowTable(testTable, "ADD COLUMN priority_score INT DEFAULT 99");

        List<String> columns = List.of("id", "customer_id", "amount", "status", "updated_at");

        // Locate java executable and classpath
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String classpath = System.getProperty("java.class.path");

        List<String> command = List.of(
                javaBin,
                "-cp", classpath,
                "com.safemigrate.core.backfill.StandaloneMigrationWorker",
                JDBC_URL, "postgres", "password", REDIS_URL,
                migrationId, testTable, shadowTable, "id",
                String.join(",", columns),
                "25", // batchSize
                "35"  // throttleMs
        );

        log.info("Launching standalone worker OS process: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        CountDownLatch checkpointReached = new CountDownLatch(1);
        List<String> processLogs = new ArrayList<>();

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processLogs.add(line);
                    log.info("[SUBPROCESS] {}", line);
                    if (line.contains(StandaloneMigrationWorker.CHECKPOINT_PREFIX)) {
                        if (line.contains("rowsCopied=")) {
                            String[] parts = line.split("rowsCopied=");
                            if (parts.length > 1) {
                                long rows = Long.parseLong(parts[1].trim());
                                if (rows >= 100) {
                                    checkpointReached.countDown();
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
        readerThread.start();

        // Wait until subprocess has backfilled at least 100 rows
        boolean reached = checkpointReached.await(15, TimeUnit.SECONDS);
        assertThat(reached).as("Subprocess should reach at least 100 rows copied").isTrue();

        // SIMULATE ABRUPT POD CRASH / OS KILL -9
        log.warn("SIMULATING HARD KILL: Calling process.destroyForcibly()...");
        process.destroyForcibly();
        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
        assertThat(exited).as("Process must be terminated").isTrue();
        assertThat(process.isAlive()).isFalse();

        log.info("Subprocess successfully killed abruptly. Verifying Redis checkpoint integrity...");

        Long lastCopiedPk = stateStore.getLastCopiedPk(migrationId);
        long rowsBackfilled = stateStore.getRowsBackfilled(migrationId);

        log.info("StateStore snapshot after worker crash: lastPk={}, rowsBackfilled={}", lastCopiedPk, rowsBackfilled);
        assertThat(lastCopiedPk).isNotNull().isGreaterThanOrEqualTo(100L).isLessThan(500L);
        assertThat(rowsBackfilled).isGreaterThanOrEqualTo(100L).isLessThan(500L);

        // Check shadow table row count matches the checkpoint
        int shadowCountMidCrash;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
            rs.next();
            shadowCountMidCrash = rs.getInt(1);
        }
        log.info("Shadow table row count immediately after crash: {}", shadowCountMidCrash);
        assertThat(shadowCountMidCrash).isGreaterThanOrEqualTo(100).isLessThan(500);

        // Release the dead worker's distributed lock to simulate failover
        stateStore.forceReleaseTableLock(testTable);

        log.info("=== Launching RECOVERY WORKER from Redis checkpoint ===");
        try (BackfillWorker recoveryWorker = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable,
                "id", columns, 50, 10
        )) {
            recoveryWorker.runBackfill();
        }

        // Verify final consistency
        int finalShadowCount;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
            rs.next();
            finalShadowCount = rs.getInt(1);
        }
        log.info("Final shadow table row count after recovery: {}", finalShadowCount);
        assertThat(finalShadowCount).isEqualTo(totalRows);

        // Verify all 500 rows have the default priority_score = 99
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable + " WHERE priority_score = 99")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(totalRows);
        }

        assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);
    }

    @Test
    void shouldRecoverDistributedLockAfterWorkerDeathViaLeaseExpiry() throws Exception {
        log.info("=== TEST 2: Distributed Lock Lease Auto-Expiry and Standby Takeover ===");

        // Worker 1 acquires lock with a short 3-second lease
        RLock worker1Lock = stateStore.acquireTableLock(testTable, 1, 3);
        assertThat(stateStore.isTableLocked(testTable)).isTrue();

        // Standby worker (on another thread / client) attempts immediate acquisition -> fails fast (prevents split-brain)
        java.util.concurrent.CompletableFuture<Void> standbyAttempt = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (StateStore standbyStore = new StateStore(REDIS_URL)) {
                standbyStore.acquireTableLock(testTable, 1, 5);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThatThrownBy(() -> standbyAttempt.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Failed to acquire migration lock for table '" + testTable + "' on database 'default'. Another migration is currently active.");

        // Worker 1 "dies" abruptly without unlocking (worker1Lock.unlock() is NEVER called).
        log.info("Worker 1 died without unlocking. Waiting 3.5s for Redisson lease auto-expiry...");
        Thread.sleep(3500);

        // Standby worker tries again after lease expiration -> acquires lock cleanly!
        java.util.concurrent.CompletableFuture<Boolean> standbyTakeover = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (StateStore standbyStore = new StateStore(REDIS_URL)) {
                RLock lock = standbyStore.acquireTableLock(testTable, 2, 10);
                boolean held = lock.isHeldByCurrentThread();
                standbyStore.releaseTableLock(lock);
                return held;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(standbyTakeover.get(5, TimeUnit.SECONDS)).isTrue();
        log.info("Standby worker successfully took over the lock after lease expiry!");
        assertThat(stateStore.isTableLocked(testTable)).isFalse();
    }

    @Test
    void shouldHandleMidBackfillCrashUnderConcurrentLiveWrites() throws Exception {
        log.info("=== TEST 3: Mid-Backfill Crash under Concurrent Live Load with Replication Catchup ===");
        int initialRows = 250;
        seedRows(initialRows);

        shadowManager.createShadowTable(testTable, "ADD COLUMN priority_score INT DEFAULT 77");

        long runId = System.currentTimeMillis();
        String slotName = "slot_res_" + runId;
        PostgresReplicationConnectionFactory repConnFactory =
                new PostgresReplicationConnectionFactory(JDBC_URL, "postgres", "password");
        repConnFactory.ensureReplicationSlotExists(slotName, "test_decoding");

        KafkaTopicManager topicManager = new KafkaTopicManager(KAFKA_BOOTSTRAP);
        topicManager.ensureTopicExists(testTable, 1, (short) 1);

        WalKafkaProducer kafkaProducer = new WalKafkaProducer(KAFKA_BOOTSTRAP, "id");
        WalReader walReader = new WalReader(
                repConnFactory, slotName, testTable, new TestDecodingDecoder(),
                kafkaProducer::send
        );
        walReader.start(null);

        Connection applierConn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        ChangeApplier changeApplier = new ChangeApplier(applierConn, stateStore, migrationId, testTable, shadowTable, "id");
        WalKafkaConsumer kafkaConsumer = new WalKafkaConsumer(KAFKA_BOOTSTRAP, "group-res-" + UUID.randomUUID(), testTable);
        changeApplier.start(kafkaConsumer);

        // Start concurrent live load
        loadGen = new LoadGenerator(JDBC_URL, "postgres", "password", testTable, 3, 25);
        loadGen.start();

        Thread.sleep(500); // Allow initial live writes

        List<String> columns = List.of("id", "customer_id", "amount", "status", "updated_at");

        // Worker 1 starts backfill but crashes abruptly at batch 3
        AtomicBoolean worker1Killed = new AtomicBoolean(false);
        try (BackfillWorker worker1 = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable,
                "id", columns, 25, 20
        )) {
            worker1.setProgressListener((lastPk, rowsCopied) -> {
                if (rowsCopied >= 75) {
                    worker1Killed.set(true);
                    throw new RuntimeException("INJECTED_WORKER_CRASH: Simulated Out-of-Memory / Pod Eviction");
                }
            });

            try {
                worker1.runBackfill();
            } catch (Exception e) {
                log.info("Worker 1 crashed as expected: {}", e.getMessage());
            }
        }

        assertThat(worker1Killed.get()).as("Worker 1 must have been killed mid-flight").isTrue();
        Long crashPk = stateStore.getLastCopiedPk(migrationId);
        log.info("Checkpoint after crash: lastPk={}", crashPk);
        assertThat(crashPk).isGreaterThanOrEqualTo(75L).isLessThan(initialRows);

        // Worker 2 starts up, recovers state, and completes backfill while load is still running
        log.info("Worker 2 resuming from checkpoint...");
        try (BackfillWorker worker2 = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable,
                "id", columns, 25, 10
        )) {
            worker2.runBackfill();
        }

        // Stop load generator
        loadGen.stop();
        assertThat(loadGen.getErrorRequests()).isZero();
        log.info("Load generator stopped. Success requests: {}", loadGen.getSuccessRequests());

        // Drain replication stream
        long lastApplied = changeApplier.getTotalApplied();
        int quiescent = 0;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            long cur = changeApplier.getTotalApplied();
            if (cur == lastApplied && cur > 0) {
                quiescent++;
                if (quiescent >= 3) break;
            } else {
                quiescent = 0;
                lastApplied = cur;
            }
        }

        walReader.stop();
        kafkaConsumer.stop();
        changeApplier.close();
        applierConn.close();
        kafkaProducer.close();
        topicManager.close();
        try {
            repConnFactory.dropReplicationSlot(slotName);
        } catch (Exception ignored) {
        }

        // Verify source and shadow consistency
        int srcCount, shadowCount;
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + testTable)) {
                rs.next();
                srcCount = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
                rs.next();
                shadowCount = rs.getInt(1);
            }
        }

        log.info("Consistency check after crash recovery & replication drain: Source={}, Shadow={}",
                srcCount, shadowCount);
        assertThat(shadowCount).isEqualTo(srcCount);
    }
}
