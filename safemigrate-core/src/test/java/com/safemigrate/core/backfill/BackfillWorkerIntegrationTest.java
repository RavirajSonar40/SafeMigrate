package com.safemigrate.core.backfill;

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
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BackfillWorkerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BackfillWorkerIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String REDIS_ADDRESS = "redis://localhost:6380";

    private String sourceTable;
    private Connection connection;
    private StateStore stateStore;
    private ShadowTableManager shadowManager;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        sourceTable = "bf_test_" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        stateStore = new StateStore(REDIS_ADDRESS);
        shadowManager = new ShadowTableManager(connection);

        // Create and seed source table with 250 rows
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE " + sourceTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL" +
                    ");");

            stmt.execute("INSERT INTO " + sourceTable + " (customer_id, amount, status) " +
                    "SELECT 'cust_' || i, (i * 1.5)::numeric(10,2), 'ACTIVE' " +
                    "FROM generate_series(1, 250) AS i;");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowManager.getShadowTableName(sourceTable) + " CASCADE;");
        } catch (Exception ignored) {
        }
        if (stateStore != null) {
            stateStore.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void shouldProvisionShadowTableAndBackfillAllRowsWithCheckpoints() throws Exception {
        String migrationId = "mig-" + UUID.randomUUID();

        // 1. Provision shadow table with target alteration: ADD COLUMN priority_score INT DEFAULT 42
        String shadowTable = shadowManager.createShadowTable(sourceTable, "ADD COLUMN priority_score INT DEFAULT 42");
        assertThat(shadowTable).isEqualTo(sourceTable + "__shadow");

        String pkCol = shadowManager.findPrimaryKeyColumn(sourceTable);
        assertThat(pkCol).isEqualTo("id");

        List<String> sourceCols = shadowManager.getColumnNames(sourceTable);
        assertThat(sourceCols).contains("id", "customer_id", "amount", "status");

        // 2. Run BackfillWorker with batchSize=50, throttle=5ms
        try (BackfillWorker worker = new BackfillWorker(
                connection,
                stateStore,
                migrationId,
                sourceTable,
                shadowTable,
                pkCol,
                sourceCols,
                50,
                5
        )) {
            worker.runBackfill();
        }

        // 3. Verify row count on the shadow table
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*), max(priority_score) FROM " + shadowTable)) {
            rs.next();
            long count = rs.getLong(1);
            int priorityVal = rs.getInt(2);

            assertThat(count).isEqualTo(250);
            assertThat(priorityVal).isEqualTo(42); // Default value applied on all backfilled rows!
        }

        // 4. Verify Redis checkpoints
        assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);
        assertThat(stateStore.getRowsBackfilled(migrationId)).isEqualTo(250);
        assertThat(stateStore.getLastCopiedPk(migrationId)).isEqualTo(250);
    }

    @Test
    void shouldResumeBackfillFromLastCheckpointAfterCrash() throws Exception {
        String migrationId = "mig-crash-" + UUID.randomUUID();
        String shadowTable = shadowManager.createShadowTable(sourceTable, "ADD COLUMN priority_score INT DEFAULT 10");
        String pkCol = shadowManager.findPrimaryKeyColumn(sourceTable);
        List<String> sourceCols = shadowManager.getColumnNames(sourceTable);

        // 1. Simulate an earlier worker that already copied 100 rows and crashed
        // Manually copy the first 100 rows into the shadow table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + shadowTable + " (id, customer_id, amount, status) " +
                    "SELECT id, customer_id, amount, status FROM " + sourceTable + " WHERE id <= 100;");
        }
        // Set checkpoint in Redis at PK=100, rows=100
        stateStore.checkpointBackfill(migrationId, 100L, 100L);

        // 2. Start a new BackfillWorker instance
        try (BackfillWorker worker = new BackfillWorker(
                connection,
                stateStore,
                migrationId,
                sourceTable,
                shadowTable,
                pkCol,
                sourceCols,
                50,
                5
        )) {
            worker.runBackfill();
        }

        // 3. Verify that the table has exactly 250 rows (no duplicates, no missing rows)
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + shadowTable)) {
            rs.next();
            long count = rs.getLong(1);
            assertThat(count).isEqualTo(250);
        }

        // 4. Verify Redis checkpoint reflects the final state
        assertThat(stateStore.getRowsBackfilled(migrationId)).isEqualTo(250);
        assertThat(stateStore.getLastCopiedPk(migrationId)).isEqualTo(250);
        assertThat(stateStore.getStatus(migrationId)).isEqualTo(MigrationState.CATCHING_UP);
    }
}
