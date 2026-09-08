package com.safemigrate.core.applier;

import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.kafka.WalKafkaConsumer;
import com.safemigrate.core.kafka.WalKafkaProducer;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.PostgresReplicationConnectionFactory;
import com.safemigrate.core.wal.TestDecodingDecoder;
import com.safemigrate.core.wal.WalReader;
import com.safemigrate.loadgen.LoadGenerator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test validating concurrency convergence:
 * 1. Seeds historical records in source table.
 * 2. Starts WAL reader and Kafka pipeline.
 * 3. Starts ChangeApplier replaying to shadow table.
 * 4. Blasts concurrent traffic (INSERT, UPDATE, DELETE) using LoadGenerator.
 * 5. Simultaneously runs BackfillWorker in throttled batches.
 * 6. After backfill and traffic stop, verifies 100% data consistency between source and shadow tables.
 */
class ConcurrencyConvergenceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConvergenceIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";
    private static final String REDIS_URL = "redis://localhost:6380";

    private String testTable;
    private String shadowTable;
    private String slotName;
    private String migrationId;

    private Connection connection;
    private PostgresReplicationConnectionFactory repConnFactory;
    private KafkaTopicManager topicManager;
    private WalKafkaProducer kafkaProducer;
    private WalKafkaConsumer kafkaConsumer;
    private WalReader walReader;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;
    private ChangeApplier changeApplier;
    private LoadGenerator loadGenerator;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "orders_cc_" + runId;
        slotName = "slot_cc_" + runId;
        migrationId = "mig-cc-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        repConnFactory = new PostgresReplicationConnectionFactory(JDBC_URL, "postgres", "password");
        topicManager = new KafkaTopicManager(KAFKA_BOOTSTRAP);
        topicManager.ensureTopicExists(testTable, 1, (short) 1);
        kafkaProducer = new WalKafkaProducer(KAFKA_BOOTSTRAP, "id");
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        // 1. Create dedicated source table with REPLICA IDENTITY FULL
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");

            // Seed 200 initial historical records
            String insertSeed = "INSERT INTO " + testTable + " (customer_id, amount, status) VALUES (?, ?, ?)";
            try (PreparedStatement seedStmt = connection.prepareStatement(insertSeed)) {
                for (int i = 1; i <= 200; i++) {
                    seedStmt.setString(1, "cust_" + i);
                    seedStmt.setBigDecimal(2, BigDecimal.valueOf(i * 5.50).setScale(2, java.math.RoundingMode.HALF_UP));
                    seedStmt.setString(3, (i % 2 == 0) ? "COMPLETED" : "PENDING");
                    seedStmt.addBatch();
                }
                seedStmt.executeBatch();
            }
        }

        // 2. Create replication slot BEFORE creating shadow table
        repConnFactory.ensureReplicationSlotExists(slotName, "test_decoding");

        // 3. Create shadow table with new column priority_score INT DEFAULT 42
        shadowTable = shadowManager.createShadowTable(testTable, "ADD COLUMN priority_score INT DEFAULT 42");

        // 4. Initialize ChangeApplier
        Connection applierConn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        changeApplier = new ChangeApplier(applierConn, stateStore, migrationId, testTable, shadowTable, "id");

        // 5. Initialize WalKafkaConsumer and wire to ChangeApplier
        String groupId = "group-cc-" + UUID.randomUUID();
        kafkaConsumer = new WalKafkaConsumer(KAFKA_BOOTSTRAP, groupId, testTable);
        changeApplier.start(kafkaConsumer);

        // 6. Start WalReader publishing to Kafka
        walReader = new WalReader(
                repConnFactory,
                slotName,
                testTable,
                new TestDecodingDecoder(),
                event -> kafkaProducer.send(event)
        );
        walReader.start(null);

        // Brief delay to allow Kafka consumer to subscribe and assign partitions
        Thread.sleep(1500);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (loadGenerator != null) {
            loadGenerator.stop();
        }
        if (walReader != null) {
            walReader.stop();
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.stop();
        }
        if (changeApplier != null) {
            changeApplier.close();
        }
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
        if (topicManager != null) {
            topicManager.close();
        }
        if (stateStore != null) {
            stateStore.close();
        }
        try {
            repConnFactory.dropReplicationSlot(slotName);
        } catch (Exception ignored) {
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void shouldAchieveZeroDataDriftUnderConcurrentTrafficAndBackfill() throws Exception {
        log.info("Starting concurrent traffic generator on table '{}'...", testTable);

        // 1. Launch concurrent background traffic simulator (4 workers @ 40 ops/sec)
        loadGenerator = new LoadGenerator(
                JDBC_URL, "postgres", "password", testTable, 4, 40
        );
        loadGenerator.start();

        // Let some initial live writes execute before starting backfill
        Thread.sleep(1000);

        // 2. Simultaneously run BackfillWorker in paged, throttled batches
        log.info("Starting BackfillWorker while load generator is blasting writes...");
        List<String> columnsToCopy = List.of("id", "customer_id", "amount", "status", "updated_at");

        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable, "id",
                columnsToCopy, 25, 20 // batchSize=25, throttleDelay=20ms
        )) {
            worker.runBackfill();
        }

        log.info("Backfill complete. Stopping load generator...");
        loadGenerator.stop();

        log.info("Load generator stopped. Total traffic requests: {}, errors: {}",
                loadGenerator.getSuccessRequests(), loadGenerator.getErrorRequests());
        assertThat(loadGenerator.getErrorRequests()).isZero();
        assertThat(loadGenerator.getSuccessRequests()).isGreaterThan(20);

        // 3. Wait for replication stream and ChangeApplier to catch up
        log.info("Waiting for ChangeApplier to catch up to all WAL changes...");
        long lastApplied = changeApplier.getTotalApplied();
        int quiescenceSeconds = 0;

        for (int i = 0; i < 20; i++) { // Max 10 seconds wait
            Thread.sleep(500);
            long current = changeApplier.getTotalApplied();
            if (current == lastApplied && current > 0) {
                quiescenceSeconds++;
                if (quiescenceSeconds >= 3) {
                    log.info("Stream quiescent. Total events applied: {}", current);
                    break;
                }
            } else {
                quiescenceSeconds = 0;
                lastApplied = current;
            }
        }

        log.info("ChangeApplier metrics: Total={}, Inserts={}, Updates={}, Deletes={}",
                changeApplier.getTotalApplied(),
                changeApplier.getInsertCount(),
                changeApplier.getUpdateCount(),
                changeApplier.getDeleteCount());

        // 4. VERIFICATION: Compare source table vs shadow table
        int sourceRowCount;
        int shadowRowCount;

        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + testTable)) {
                rs.next();
                sourceRowCount = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + shadowTable)) {
                rs.next();
                shadowRowCount = rs.getInt(1);
            }
        }

        log.info("Row count comparison: Source='{}' has {} rows | Shadow='{}' has {} rows",
                testTable, sourceRowCount, shadowTable, shadowRowCount);

        assertThat(shadowRowCount)
                .as("Shadow table row count must match source table row count exactly")
                .isEqualTo(sourceRowCount);

        // 5. Deep row-by-row consistency validation
        List<RowData> sourceRows = fetchRows(testTable);
        List<RowData> shadowRows = fetchRows(shadowTable);

        assertThat(shadowRows).hasSize(sourceRows.size());

        for (int i = 0; i < sourceRows.size(); i++) {
            RowData src = sourceRows.get(i);
            RowData shd = shadowRows.get(i);

            assertThat(shd.id()).as("Row ID mismatch at index " + i).isEqualTo(src.id());
            assertThat(shd.customerId()).as("customer_id mismatch for id=" + src.id()).isEqualTo(src.customerId());
            assertThat(shd.amount()).as("amount mismatch for id=" + src.id()).isEqualByComparingTo(src.amount());
            assertThat(shd.status()).as("status mismatch for id=" + src.id()).isEqualTo(src.status());
            assertThat(shd.priorityScore()).as("priority_score should have default 42 for id=" + src.id()).isEqualTo(42);
        }

        log.info("CONVERGENCE VERIFIED: 100% matching rows between source and shadow table with 0 data drift!");
    }

    private List<RowData> fetchRows(String tableName) throws Exception {
        List<RowData> rows = new ArrayList<>();
        boolean hasPriorityScore = tableName.endsWith("__shadow");
        String sql = hasPriorityScore
                ? "SELECT id, customer_id, amount, status, priority_score FROM " + tableName + " ORDER BY id ASC"
                : "SELECT id, customer_id, amount, status, 0 as priority_score FROM " + tableName + " ORDER BY id ASC";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new RowData(
                        rs.getLong("id"),
                        rs.getString("customer_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getInt("priority_score")
                ));
            }
        }
        return rows;
    }

    private record RowData(long id, String customerId, BigDecimal amount, String status, int priorityScore) {
    }
}
