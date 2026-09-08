package com.safemigrate.core.preflight;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class PreflightInspectorTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";

    private Connection connection;
    private PreflightInspector inspector;
    private String testTable;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "orders_preflight_" + runId;

        connection = DriverManager.getConnection(JDBC_URL, "postgres", "password");
        inspector = new PreflightInspector(connection);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS test_no_pk CASCADE;");
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void shouldPassAllChecksForValidTableAndDdl() throws Exception {
        PreflightReport report = inspector.inspect(testTable, "ADD COLUMN priority_score INT DEFAULT 10");

        assertThat(report.passed()).isTrue();
        assertThat(report.hasErrors()).isFalse();
        assertThat(report.primaryKeyColumn()).isEqualTo("id");
        assertThat(report.replicaIdentityFull()).isTrue();
        assertThat(report.estimatedRequiredDiskBytes()).isGreaterThan(0L);
    }

    @Test
    void shouldFailWhenTableDoesNotExist() throws Exception {
        PreflightReport report = inspector.inspect("non_existent_table_xyz", "ADD COLUMN foo INT");

        assertThat(report.passed()).isFalse();
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.getErrors())
                .extracting(PreflightIssue::code)
                .contains("TABLE_NOT_FOUND");
    }

    @Test
    void shouldFailWhenTableHasNoPrimaryKey() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_no_pk CASCADE;");
            stmt.execute("CREATE TABLE test_no_pk (name VARCHAR(50), value INT);");
        }

        PreflightReport report = inspector.inspect("test_no_pk", "ADD COLUMN foo INT");

        assertThat(report.passed()).isFalse();
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.getErrors())
                .extracting(PreflightIssue::code)
                .contains("NO_PRIMARY_KEY");
    }

    @Test
    void shouldWarnWhenReplicaIdentityNotFull() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY DEFAULT;");
        }

        PreflightReport report = inspector.inspect(testTable, "ADD COLUMN notes TEXT DEFAULT ''");

        // Should still pass, but contain a warning
        assertThat(report.passed()).isTrue();
        assertThat(report.replicaIdentityFull()).isFalse();
        assertThat(report.hasWarnings()).isTrue();
        assertThat(report.getWarnings())
                .extracting(PreflightIssue::code)
                .contains("REPLICA_IDENTITY_NOT_FULL");
    }

    @Test
    void shouldRejectMultiStatementSqlInjection() throws Exception {
        String injectedDdl = "ADD COLUMN extra INT; DROP TABLE " + testTable + "; --";

        PreflightReport report = inspector.inspect(testTable, injectedDdl);

        assertThat(report.passed()).isFalse();
        assertThat(report.getErrors())
                .extracting(PreflightIssue::code)
                .contains("SQL_INJECTION_DETECTED");
    }

    @Test
    void shouldRejectDestructiveDdlStatements() throws Exception {
        PreflightReport report1 = inspector.inspect(testTable, "TRUNCATE TABLE " + testTable);
        assertThat(report1.passed()).isFalse();
        assertThat(report1.getErrors()).extracting(PreflightIssue::code).contains("PROHIBITED_DDL_OPERATION");

        PreflightReport report2 = inspector.inspect(testTable, "DROP DATABASE safemigrate_test");
        assertThat(report2.passed()).isFalse();
        assertThat(report2.getErrors()).extracting(PreflightIssue::code).contains("PROHIBITED_DDL_OPERATION");
    }

    @Test
    void shouldRejectNotNullWithoutDefaultOnNonEmptyTable() throws Exception {
        // Insert a historical record so table is non-empty
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + testTable + " (customer_id, amount) VALUES ('cust1', 50.00)");
        }

        PreflightReport report = inspector.inspect(testTable, "ADD COLUMN tracking_code VARCHAR(32) NOT NULL");

        assertThat(report.passed()).isFalse();
        assertThat(report.getErrors())
                .extracting(PreflightIssue::code)
                .contains("NOT_NULL_WITHOUT_DEFAULT");
    }

    @Test
    void shouldAllowNotNullWithDefaultOnNonEmptyTable() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + testTable + " (customer_id, amount) VALUES ('cust1', 50.00)");
        }

        PreflightReport report = inspector.inspect(testTable, "ADD COLUMN tracking_code VARCHAR(32) NOT NULL DEFAULT 'UNASSIGNED'");

        assertThat(report.passed()).isTrue();
        assertThat(report.hasErrors()).isFalse();
    }
}
