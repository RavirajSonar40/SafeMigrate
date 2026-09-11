package com.safemigrate.core.cutover;

import com.safemigrate.core.applier.ChangeApplier;
import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.kafka.WalKafkaConsumer;
import com.safemigrate.core.kafka.WalKafkaProducer;
import com.safemigrate.core.reconcile.ReconciliationReport;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.PostgresReplicationConnectionFactory;
import com.safemigrate.core.wal.TestDecodingDecoder;
import com.safemigrate.core.wal.WalReader;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates complex schema mutations without downtime or locking:
 * 1. Column Type Alteration: NUMERIC(10,2) -> NUMERIC(14,4) and VARCHAR(32) -> VARCHAR(100)
 * 2. Adding NOT NULL constraint with backfill default: ADD COLUMN priority_level INT NOT NULL DEFAULT 1
 * 3. Live writes during backfill and automatic type casting in ChangeApplier
 * 4. Post-cutover mathematical reconciliation audit
 */
class ColumnTypeChangeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ColumnTypeChangeIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";
    private static final String REDIS_URL = "redis://localhost:6380";

    private String testTable;
    private String shadowTable;
    private String oldTable;
    private String slotName;
    private String migrationId;

    private Connection connection;
    private Connection applierConn;
    private Connection cutoverConn;
    private PostgresReplicationConnectionFactory repConnFactory;
    private KafkaTopicManager topicManager;
    private WalKafkaProducer kafkaProducer;
    private WalKafkaConsumer kafkaConsumer;
    private WalReader walReader;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;
    private ChangeApplier changeApplier;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "orders_type_change_" + runId;
        shadowTable = testTable + "__shadow";
        oldTable = testTable + "__old";
        slotName = "slot_type_" + runId;
        migrationId = "mig-type-" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        applierConn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        cutoverConn = DriverManager.getConnection(JDBC_URL, "postgres", "password");

        repConnFactory = new PostgresReplicationConnectionFactory(JDBC_URL, "postgres", "password");
        topicManager = new KafkaTopicManager(KAFKA_BOOTSTRAP);
        topicManager.ensureTopicExists(testTable, 1, (short) 1);
        kafkaProducer = new WalKafkaProducer(KAFKA_BOOTSTRAP, "id");
        stateStore = new StateStore(REDIS_URL);
        shadowManager = new ShadowTableManager(connection);

        // 1. Create source table with narrow types
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");

            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "order_code VARCHAR(32) NOT NULL, " +
                    "total_amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(16) NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");

            // Seed 200 initial historical records
            String insertSeed = "INSERT INTO " + testTable + " (order_code, total_amount, status) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(insertSeed)) {
                for (int i = 1; i <= 200; i++) {
                    ps.setString(1, "ORD_" + i);
                    ps.setBigDecimal(2, BigDecimal.valueOf(i * 15.50).setScale(2, java.math.RoundingMode.HALF_UP));
                    ps.setString(3, "PENDING");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        // 2. Provision replication slot BEFORE creating shadow table
        repConnFactory.ensureReplicationSlotExists(slotName, "test_decoding");
    }

    @AfterEach
    void tearDown() {
        try {
            if (walReader != null) walReader.close();
            if (changeApplier != null) changeApplier.close();
            if (kafkaConsumer != null) kafkaConsumer.close();
            if (kafkaProducer != null) kafkaProducer.close();
            if (stateStore != null) stateStore.close();
            if (repConnFactory != null) repConnFactory.dropReplicationSlot(slotName);
        } catch (Exception ignored) {}

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
        } catch (Exception ignored) {}

        try {
            if (connection != null && !connection.isClosed()) connection.close();
            if (applierConn != null && !applierConn.isClosed()) applierConn.close();
            if (cutoverConn != null && !cutoverConn.isClosed()) cutoverConn.close();
        } catch (Exception ignored) {}
    }

    @Test
    void testColumnTypeChangeAndNotNullConstraintMigration() throws Exception {
        log.info("Starting Column Type Change and NOT NULL Constraint Migration Test...");

        // 1. Apply DDL to Shadow Table:
        // - Expand order_code from VARCHAR(32) to VARCHAR(100)
        // - Expand total_amount from NUMERIC(10,2) to NUMERIC(14,4)
        // - Add priority_level INT NOT NULL DEFAULT 1
        String ddl = "ALTER COLUMN total_amount TYPE NUMERIC(14, 4); " +
                     "ALTER COLUMN order_code TYPE VARCHAR(100); " +
                     "ADD COLUMN priority_level INT NOT NULL DEFAULT 1";

        String createdShadow = shadowManager.createShadowTable(testTable, ddl);
        assertThat(createdShadow).isEqualTo(shadowTable);

        // Verify shadow table metadata has new types
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable
                 FROM information_schema.columns
                 WHERE table_name = '""" + shadowTable + "' AND table_schema = 'public'")) {
            boolean foundAmount = false;
            boolean foundCode = false;
            boolean foundPriority = false;

            while (rs.next()) {
                String col = rs.getString("column_name");
                if ("total_amount".equals(col)) {
                    assertThat(rs.getInt("numeric_precision")).isEqualTo(14);
                    assertThat(rs.getInt("numeric_scale")).isEqualTo(4);
                    foundAmount = true;
                } else if ("order_code".equals(col)) {
                    assertThat(rs.getInt("character_maximum_length")).isEqualTo(100);
                    foundCode = true;
                } else if ("priority_level".equals(col)) {
                    assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                    foundPriority = true;
                }
            }
            assertThat(foundAmount).isTrue();
            assertThat(foundCode).isTrue();
            assertThat(foundPriority).isTrue();
        }

        // 2. Start WAL streaming and ChangeApplier
        changeApplier = new ChangeApplier(applierConn, stateStore, migrationId, testTable, shadowTable, "id");
        kafkaConsumer = new WalKafkaConsumer(KAFKA_BOOTSTRAP, "type-cg-" + System.currentTimeMillis(), testTable);
        changeApplier.start(kafkaConsumer);

        walReader = new WalReader(repConnFactory, slotName, testTable, new TestDecodingDecoder(), kafkaProducer::send);
        walReader.start(null);

        // Allow consumer rebalance
        Thread.sleep(1000);

        // 3. Concurrently insert live writes on the source table using the old schema
        try (PreparedStatement livePs = connection.prepareStatement(
                "INSERT INTO " + testTable + " (order_code, total_amount, status) VALUES (?, ?, ?)")) {
            for (int i = 201; i <= 250; i++) {
                livePs.setString(1, "LIVE_ORD_" + i);
                livePs.setBigDecimal(2, BigDecimal.valueOf(99.95).setScale(2, java.math.RoundingMode.HALF_UP));
                livePs.setString(3, "LIVE_PROCESSING");
                livePs.addBatch();
            }
            livePs.executeBatch();
        }

        // Also update a historical row in the source table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("UPDATE " + testTable + " SET status = 'SHIPPED', total_amount = 77.77 WHERE id = 10;");
        }

        // 4. Run BackfillWorker
        List<String> sourceCols = shadowManager.getColumnNames(testTable);
        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable, "id", sourceCols, 50, 0)) {
            worker.runBackfill();
            assertThat(worker.getRowsBackfilled()).isGreaterThanOrEqualTo(200);
        }

        // 5. Let CDC drain
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (changeApplier.getInsertCount() >= 50 && changeApplier.getUpdateCount() >= 1) {
                break;
            }
            Thread.sleep(200);
        }

        log.info("CDC catchup stats: inserts={}, updates={}, totalApplied={}",
                changeApplier.getInsertCount(), changeApplier.getUpdateCount(), changeApplier.getTotalApplied());

        // 6. Execute Cutover via CutoverCoordinator
        CutoverCoordinator coordinator = new CutoverCoordinator(
                cutoverConn, stateStore, migrationId, testTable, shadowTable, oldTable, 2000L
        );
        long cutoverMs = coordinator.executeCutover();
        log.info("Column type change cutover finished in {} ms", cutoverMs);
        assertThat(cutoverMs).isLessThan(2000L);

        // 7. Post-Cutover Verification:
        // Verify source table has been promoted with new column definitions
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable
                 FROM information_schema.columns
                 WHERE table_name = '""" + testTable + "' AND table_schema = 'public'")) {
            boolean foundAmount = false;
            boolean foundCode = false;
            boolean foundPriority = false;

            while (rs.next()) {
                String col = rs.getString("column_name");
                if ("total_amount".equals(col)) {
                    assertThat(rs.getInt("numeric_precision")).isEqualTo(14);
                    assertThat(rs.getInt("numeric_scale")).isEqualTo(4);
                    foundAmount = true;
                } else if ("order_code".equals(col)) {
                    assertThat(rs.getInt("character_maximum_length")).isEqualTo(100);
                    foundCode = true;
                } else if ("priority_level".equals(col)) {
                    assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                    foundPriority = true;
                }
            }
            assertThat(foundAmount).isTrue();
            assertThat(foundCode).isTrue();
            assertThat(foundPriority).isTrue();
        }

        // Verify total row count is 250
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + testTable)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(250);
        }

        // Verify priority_level defaults to 1 for all rows
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + testTable + " WHERE priority_level <> 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(0);
        }

        // Verify updated row 10 has SHIPPED and total_amount = 77.7700
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status, total_amount FROM " + testTable + " WHERE id = 10")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("SHIPPED");
            assertThat(rs.getBigDecimal("total_amount")).isEqualByComparingTo("77.77");
        }

        // 8. Verify post-cutover data reconciliation report stored in Redis
        String reportJson = stateStore.getReconciliationReport(migrationId);
        assertThat(reportJson).isNotNull();
        ReconciliationReport report = ReconciliationReport.fromJson(reportJson);
        log.info("Reconciliation Report from Redis: matched={}, rows={}, checksum={}",
                report.isMatched(), report.getSourceRowCount(), report.getSourceChecksum());

        assertThat(report.isMatched()).isTrue();
        assertThat(report.getSourceRowCount()).isEqualTo(250);
        assertThat(report.getTargetRowCount()).isEqualTo(250);
        assertThat(report.getDiscrepancyCount()).isEqualTo(0);
    }
}
