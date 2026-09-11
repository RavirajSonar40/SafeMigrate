package com.safemigrate.core.reconcile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for DataReconciliationService.
 * Validates 100% mathematical data parity verification between tables,
 * cross-type compatibility (NUMERIC scale and VARCHAR length expansion),
 * and precision detection of injected data discrepancies.
 */
class ReconciliationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationIntegrationTest.class);
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";

    private Connection connection;
    private String tableA;
    private String tableB;
    private DataReconciliationService reconciliationService;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        reconciliationService = new DataReconciliationService(connection);

        long now = System.currentTimeMillis();
        tableA = "recon_src_" + now;
        tableB = "recon_tgt_" + now;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableA + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + tableB + " CASCADE;");

            stmt.execute("CREATE TABLE " + tableA + " (" +
                    "id BIGINT PRIMARY KEY, " +
                    "account_code VARCHAR(32) NOT NULL, " +
                    "balance NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(16) NOT NULL" +
                    ");");

            // tableB simulates shadow table with expanded data types and an added column
            stmt.execute("CREATE TABLE " + tableB + " (" +
                    "id BIGINT PRIMARY KEY, " +
                    "account_code VARCHAR(100) NOT NULL, " +
                    "balance NUMERIC(14, 4) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL, " +
                    "is_verified BOOLEAN DEFAULT true" +
                    ");");

            // Populate 200 identical records into both tables
            String sqlA = "INSERT INTO " + tableA + " (id, account_code, balance, status) VALUES (?, ?, ?, ?)";
            String sqlB = "INSERT INTO " + tableB + " (id, account_code, balance, status, is_verified) VALUES (?, ?, ?, ?, ?)";

            try (PreparedStatement psA = connection.prepareStatement(sqlA);
                 PreparedStatement psB = connection.prepareStatement(sqlB)) {
                for (int i = 1; i <= 200; i++) {
                    psA.setLong(1, i);
                    psA.setString(2, "ACCT_" + i);
                    psA.setBigDecimal(3, BigDecimal.valueOf(i * 19.99).setScale(2, java.math.RoundingMode.HALF_UP));
                    psA.setString(4, (i % 2 == 0) ? "ACTIVE" : "PENDING");
                    psA.addBatch();

                    psB.setLong(1, i);
                    psB.setString(2, "ACCT_" + i);
                    psB.setBigDecimal(3, BigDecimal.valueOf(i * 19.99).setScale(4, java.math.RoundingMode.HALF_UP));
                    psB.setString(4, (i % 2 == 0) ? "ACTIVE" : "PENDING");
                    psB.setBoolean(5, true);
                    psB.addBatch();
                }
                psA.executeBatch();
                psB.executeBatch();
            }
        }
    }

    @AfterEach
    void tearDown() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableA + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + tableB + " CASCADE;");
        } catch (Exception ignored) {}
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (Exception ignored) {}
    }

    @Test
    void testReconciliationPassesForIdenticalDataAcrossExpandedTypes() throws Exception {
        ReconciliationReport report = reconciliationService.reconcile(tableA, tableB);

        log.info("Reconciliation result: matched={}, time={}ms, checksum={}",
                report.isMatched(), report.getExecutionTimeMs(), report.getSourceChecksum());

        assertThat(report.isMatched()).isTrue();
        assertThat(report.getSourceRowCount()).isEqualTo(200);
        assertThat(report.getTargetRowCount()).isEqualTo(200);
        assertThat(report.getSourceChecksum()).isEqualTo(report.getTargetChecksum());
        assertThat(report.getDiscrepancyCount()).isEqualTo(0);
        assertThat(report.getSampleDiscrepancies()).isEmpty();
        assertThat(report.getComparedColumns()).containsExactlyInAnyOrder("id", "account_code", "balance", "status");
    }

    @Test
    void testReconciliationDetectsCorruptedValueDiscrepancy() throws Exception {
        // Corrupt row id=42 in tableB
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("UPDATE " + tableB + " SET balance = 9999.99 WHERE id = 42;");
        }

        ReconciliationReport report = reconciliationService.reconcile(tableA, tableB);

        log.info("Corrupted reconciliation result: matched={}, discrepancies={}",
                report.isMatched(), report.getSampleDiscrepancies());

        assertThat(report.isMatched()).isFalse();
        assertThat(report.getSourceRowCount()).isEqualTo(200);
        assertThat(report.getTargetRowCount()).isEqualTo(200);
        assertThat(report.getSourceChecksum()).isNotEqualTo(report.getTargetChecksum());
        assertThat(report.getSampleDiscrepancies()).isNotEmpty();
        assertThat(report.getSampleDiscrepancies().get(0)).contains("42");
    }

    @Test
    void testReconciliationDetectsMissingRowDiscrepancy() throws Exception {
        // Delete row id=100 from tableB
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM " + tableB + " WHERE id = 100;");
        }

        ReconciliationReport report = reconciliationService.reconcile(tableA, tableB);

        log.info("Missing row reconciliation result: matched={}, srcRows={}, tgtRows={}",
                report.isMatched(), report.getSourceRowCount(), report.getTargetRowCount());

        assertThat(report.isMatched()).isFalse();
        assertThat(report.getSourceRowCount()).isEqualTo(200);
        assertThat(report.getTargetRowCount()).isEqualTo(199);
        assertThat(report.getSourceChecksum()).isNotEqualTo(report.getTargetChecksum());
        assertThat(report.getDiscrepancyCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.getSampleDiscrepancies()).isNotEmpty();
        assertThat(report.getSampleDiscrepancies().stream().anyMatch(s -> s.contains("100"))).isTrue();
    }
}
