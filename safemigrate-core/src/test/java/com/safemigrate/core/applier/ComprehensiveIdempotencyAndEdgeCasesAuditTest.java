package com.safemigrate.core.applier;

import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.cutover.CutoverCoordinator;
import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.TestDecodingDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep-Dive Idempotency & Edge-Cases Audit Test Suite:
 * Rigorously validates:
 * 1. Escaped single quotes ("O'Reilly") and multi-byte Unicode.
 * 2. Primary Key mutations (UPDATE modifying PK value).
 * 3. Double-cutover idempotency (calling executeCutover repeatedly).
 * 4. Rapid repeated UPDATE replay idempotency.
 * 5. Reused Primary Key (INSERT -> DELETE -> re-INSERT).
 * 6. Complex PostgreSQL data types (UUID, TIMESTAMPTZ, BOOLEAN, NUMERIC).
 * 7. Replication lag boundary evaluation when LSN is null or catching up.
 * 8. Pre-cutover Rollback idempotency (calling rollback multiple times).
 */
class ComprehensiveIdempotencyAndEdgeCasesAuditTest {

    private static final Logger log = LoggerFactory.getLogger(ComprehensiveIdempotencyAndEdgeCasesAuditTest.class);

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
        testTable = "orders_audit_" + runId;
        shadowTable = testTable + "__shadow";
        oldTable = testTable + "__old";
        migrationId = "mig-audit-" + runId;

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
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void shouldHandleEscapedSingleQuotesAndUnicodeInStringColumns() throws Exception {
        log.info("=== AUDIT TEST 1: Escaped Single Quotes and Unicode Decoding ===");

        TestDecodingDecoder decoder = new TestDecodingDecoder();

        // Raw test_decoding payload containing escaped single quotes (O''Reilly) and unicode
        String rawWal = "table public." + testTable + ": INSERT: id[bigint]:1 customer_id[character varying]:'O''Reilly''s Pub' amount[numeric]:99.50 status[character varying]:'DELIVERED 🚀'";
        ByteBuffer buffer = ByteBuffer.wrap(rawWal.getBytes(StandardCharsets.UTF_8));

        Optional<WalChangeEvent> decodedOpt = decoder.decode(buffer, 1000L);
        assertThat(decodedOpt).isPresent();

        WalChangeEvent event = decodedOpt.get();
        assertThat(event.newValues().get("customer_id")).isEqualTo("O'Reilly's Pub");
        assertThat(event.newValues().get("status")).isEqualTo("DELIVERED 🚀");

        // Now replay onto shadow table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, customer_id VARCHAR(64), amount NUMERIC(10, 2), status VARCHAR(64));");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN audit_flag BOOLEAN DEFAULT true");

        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, testTable, shadowTable, "id")) {
            applier.apply(event);
        }

        // Verify inserted into shadow table with unescaped quote and unicode
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT customer_id, status FROM " + shadowTable + " WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("customer_id")).isEqualTo("O'Reilly's Pub");
            assertThat(rs.getString("status")).isEqualTo("DELIVERED 🚀");
        }
    }

    @Test
    void shouldHandlePrimaryKeyMutationInUpdateEvent() throws Exception {
        log.info("=== AUDIT TEST 2: Primary Key Mutation Handling in ChangeApplier ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGINT PRIMARY KEY, customer_id VARCHAR(64), amount NUMERIC(10, 2));");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN score INT DEFAULT 0");

        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, testTable, shadowTable, "id")) {
            // Initial insert of row with PK = 100
            WalChangeEvent insertEvent = new WalChangeEvent(
                    testTable, OperationType.INSERT, Map.of(),
                    Map.of("id", 100, "customer_id", "cust_100", "amount", 45.00),
                    101L, Instant.now()
            );
            applier.apply(insertEvent);

            // Mutation: UPDATE modifying PK from 100 to 200
            WalChangeEvent pkMutationEvent = new WalChangeEvent(
                    testTable, OperationType.UPDATE,
                    Map.of("id", 100, "customer_id", "cust_100", "amount", 45.00),
                    Map.of("id", 200, "customer_id", "cust_100", "amount", 55.00),
                    102L, Instant.now()
            );
            applier.apply(pkMutationEvent);
        }

        try (Statement stmt = connection.createStatement()) {
            // Row 100 must NO LONGER exist
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable + " WHERE id = 100")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            // Row 200 must exist with new amount
            try (ResultSet rs = stmt.executeQuery("SELECT amount, customer_id FROM " + shadowTable + " WHERE id = 200")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("55.00");
                assertThat(rs.getString("customer_id")).isEqualTo("cust_100");
            }
        }
    }

    @Test
    void shouldBeIdempotentOnDuplicateCutoverCalls() throws Exception {
        log.info("=== AUDIT TEST 3: Double-Cutover Idempotency ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, name VARCHAR(64));");
            stmt.execute("INSERT INTO " + testTable + " (name) VALUES ('original');");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN v INT DEFAULT 1");

        // Backfill the row into shadow table
        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable, "id", List.of("id", "name"), 10, 0
        )) {
            worker.runBackfill();
        }

        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, testTable, shadowTable, oldTable, 2000L
        )) {
            // First cutover
            long duration1 = coordinator.executeCutover();
            assertThat(duration1).isGreaterThanOrEqualTo(0L);
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.COMPLETED);

            // Second cutover (idempotent call!)
            long duration2 = coordinator.executeCutover();
            assertThat(duration2).isEqualTo(0L);
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.COMPLETED);
        }

        // Verify promoted table still intact and accessible
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + testTable)) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void shouldHandleRapidOverlappingUpsertsOnSameRow() throws Exception {
        log.info("=== AUDIT TEST 4: Rapid Overlapping Replay on Same Row ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGINT PRIMARY KEY, status VARCHAR(32));");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN revision INT DEFAULT 1");

        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, testTable, shadowTable, "id")) {
            // Replay 10 successive updates
            for (int rev = 1; rev <= 10; rev++) {
                WalChangeEvent event = new WalChangeEvent(
                        testTable, OperationType.UPDATE,
                        Map.of("id", 1, "status", "REV_" + (rev - 1)),
                        Map.of("id", 1, "status", "REV_" + rev),
                        200L + rev, Instant.now()
                );
                applier.apply(event);
            }

            // Triple replay of REV_10 (idempotency f(f(x)) = f(x))
            WalChangeEvent finalEvent = new WalChangeEvent(
                    testTable, OperationType.UPDATE,
                    Map.of("id", 1, "status", "REV_9"),
                    Map.of("id", 1, "status", "REV_10"),
                    210L, Instant.now()
            );
            applier.apply(finalEvent);
            applier.apply(finalEvent);
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM " + shadowTable + " WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("REV_10");
        }
    }

    @Test
    void shouldHandleDeleteThenInsertWithReusedPrimaryKey() throws Exception {
        log.info("=== AUDIT TEST 5: Reused Primary Key (INSERT -> DELETE -> re-INSERT) ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGINT PRIMARY KEY, owner VARCHAR(64), balance NUMERIC(10, 2));");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN active BOOLEAN DEFAULT true");

        try (ChangeApplier applier = new ChangeApplier(connection, stateStore, migrationId, testTable, shadowTable, "id")) {
            // 1. Initial INSERT
            applier.apply(new WalChangeEvent(
                    testTable, OperationType.INSERT, Map.of(),
                    Map.of("id", 777, "owner", "Alice", "balance", 100.00),
                    301L, Instant.now()
            ));

            // 2. DELETE
            applier.apply(new WalChangeEvent(
                    testTable, OperationType.DELETE,
                    Map.of("id", 777, "owner", "Alice", "balance", 100.00),
                    Map.of(), 302L, Instant.now()
            ));

            // 3. re-INSERT with completely different owner and balance
            applier.apply(new WalChangeEvent(
                    testTable, OperationType.INSERT, Map.of(),
                    Map.of("id", 777, "owner", "Bob", "balance", 999.99),
                    303L, Instant.now()
            ));
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT owner, balance FROM " + shadowTable + " WHERE id = 777")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("owner")).isEqualTo("Bob");
            assertThat(rs.getBigDecimal("balance")).isEqualByComparingTo("999.99");
        }
    }

    @Test
    void shouldHandleBackfillWithComplexDataTypes() throws Exception {
        log.info("=== AUDIT TEST 6: Complex PostgreSQL Data Types (UUID, TIMESTAMPTZ, BOOLEAN, NUMERIC) ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "txn_uuid UUID NOT NULL, " +
                    "created_at TIMESTAMPTZ NOT NULL, " +
                    "is_settled BOOLEAN NOT NULL, " +
                    "amount NUMERIC(12, 4) NOT NULL" +
                    ");");

            // Seed complex rows
            String insertSql = "INSERT INTO " + testTable + " (txn_uuid, created_at, is_settled, amount) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                for (int i = 1; i <= 20; i++) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, java.sql.Timestamp.from(Instant.now()));
                    ps.setBoolean(3, i % 2 == 0);
                    ps.setBigDecimal(4, BigDecimal.valueOf(i * 100.1234));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        shadowManager.createShadowTable(testTable, "ADD COLUMN audit_hash VARCHAR(64) DEFAULT 'hash_val'");

        List<String> columns = List.of("id", "txn_uuid", "created_at", "is_settled", "amount");
        try (BackfillWorker worker = new BackfillWorker(
                connection, stateStore, migrationId, testTable, shadowTable,
                "id", columns, 10, 10
        )) {
            worker.runBackfill();
        }

        // Verify all 20 rows backfilled into shadow table
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(20);
        }
    }

    @Test
    void shouldHandleCutoverLagEvaluationEdgeCases() throws Exception {
        log.info("=== AUDIT TEST 7: Cutover Lag Evaluation Edge Cases ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, val INT);");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN col2 INT DEFAULT 0");

        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, testTable, shadowTable, oldTable, 2000L
        )) {
            // When no LSN has been recorded in Redis, lag should be Long.MAX_VALUE and NOT ready
            assertThat(coordinator.getReplicationLagBytes()).isEqualTo(Long.MAX_VALUE);
            assertThat(coordinator.isReadyForCutover(1024L)).isFalse();

            // Set state to CATCHING_UP and record current Postgres LSN in Redis
            stateStore.setStatus(migrationId, MigrationState.CATCHING_UP);

            long currentPgLsn;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT pg_current_wal_lsn()")) {
                rs.next();
                currentPgLsn = org.postgresql.replication.LogSequenceNumber.valueOf(rs.getString(1)).asLong();
            }

            stateStore.checkpointAppliedLsn(migrationId, currentPgLsn);

            // Now lag should be 0 bytes and ready for cutover!
            long lag = coordinator.getReplicationLagBytes();
            assertThat(lag).isEqualTo(0L);
            assertThat(coordinator.isReadyForCutover(1024L)).isTrue();
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.READY_CUTOVER);
        }
    }

    @Test
    void shouldSafelyHandleRollbackCalledMultipleTimes() throws Exception {
        log.info("=== AUDIT TEST 8: Repeated Pre-Cutover Rollback Idempotency ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, val INT);");
        }
        shadowManager.createShadowTable(testTable, "ADD COLUMN col2 INT DEFAULT 0");

        try (CutoverCoordinator coordinator = new CutoverCoordinator(
                connection, stateStore, migrationId, testTable, shadowTable, oldTable, 2000L
        )) {
            stateStore.setStatus(migrationId, MigrationState.BACKFILLING);

            // First rollback
            coordinator.rollback();
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.ROLLED_BACK);

            // Second rollback (idempotent call!)
            coordinator.rollback();
            assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.ROLLED_BACK);
        }

        // Verify shadow table is dropped
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM information_schema.tables WHERE table_name = '" + shadowTable + "'")) {
            assertThat(rs.next()).isFalse();
        }

        // Verify source table is completely intact
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM information_schema.tables WHERE table_name = '" + testTable + "'")) {
            assertThat(rs.next()).isTrue();
        }
    }
}
