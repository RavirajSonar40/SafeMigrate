package com.safemigrate.core.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the creation, schema provisioning, and teardown of the shadow table.
 */
public class ShadowTableManager {

    private static final Logger log = LoggerFactory.getLogger(ShadowTableManager.class);

    private final Connection connection;

    public ShadowTableManager(Connection connection) {
        this.connection = connection;
    }

    /**
     * Derives the shadow table name from the source table name.
     */
    public String getShadowTableName(String sourceTable) {
        return sourceTable + "__shadow";
    }

    /**
     * Inspects PostgreSQL metadata to find the primary key column name of the source table.
     */
    public String findPrimaryKeyColumn(String sourceTable) throws SQLException {
        String sql = """
            SELECT a.attname
            FROM   pg_index i
            JOIN   pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
            WHERE  i.indrelid = ?::regclass
            AND    i.indisprimary
            LIMIT 1;
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, sourceTable);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        throw new IllegalStateException("Table '" + sourceTable + "' has no primary key. SafeMigrate requires a primary key.");
    }

    /**
     * Fetches all column names of the source table in order.
     */
    public List<String> getColumnNames(String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position;
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    /**
     * Creates the shadow table by copying the structure of the source table and applying the schema alteration.
     *
     * @param sourceTable Name of the active production table
     * @param targetAlterDdl The DDL alteration to apply to the shadow table (e.g. "ADD COLUMN priority_score INT DEFAULT 0")
     * @return The created shadow table name
     */
    public String createShadowTable(String sourceTable, String targetAlterDdl) throws SQLException {
        String shadowTable = getShadowTableName(sourceTable);

        try (Statement stmt = connection.createStatement()) {
            // 1. Clean up any existing shadow table from a prior aborted run
            log.info("Dropping any existing shadow table '{}'...", shadowTable);
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");

            // 2. Create the shadow table cloning all defaults, constraints, and indexes
            log.info("Creating shadow table '{}' cloned from '{}'...", shadowTable, sourceTable);
            stmt.execute("CREATE TABLE " + shadowTable + " (LIKE " + sourceTable + " INCLUDING ALL);");

            // 3. Apply the requested schema alteration to the shadow table
            if (targetAlterDdl != null && !targetAlterDdl.isBlank()) {
                String[] statements = targetAlterDdl.split(";");
                for (String raw : statements) {
                    String clean = raw.replaceAll("(?m)^--.*$", "").trim();
                    if (clean.isEmpty()) continue;
                    String fullSql;
                    if (clean.toUpperCase().startsWith("ALTER TABLE")) {
                        fullSql = clean.replaceFirst("(?i)ALTER\\s+TABLE\\s+([\"'a-zA-Z0-9_]+)", "ALTER TABLE " + shadowTable) + ";";
                    } else if (clean.toUpperCase().startsWith("CREATE")) {
                        // Strip CONCURRENTLY for shadow table as it is an isolated unshared table and CONCURRENTLY fails inside transactions/poolers
                        String stripped = clean.replaceAll("(?i)\\bCONCURRENTLY\\b", "").replaceAll("\\s{2,}", " ").trim();
                        fullSql = stripped.replaceAll("(?i)(ON\\s+)([\"'a-zA-Z0-9_]+)", "$1" + shadowTable) + ";";
                    } else {
                        fullSql = "ALTER TABLE " + shadowTable + " " + clean + ";";
                    }
                    log.info("Applying target schema change: {}", fullSql);
                    stmt.execute(fullSql);
                }
            }

            // 4. Ensure REPLICA IDENTITY FULL on the shadow table
            stmt.execute("ALTER TABLE " + shadowTable + " REPLICA IDENTITY FULL;");
            log.info("Successfully provisioned shadow table: {}", shadowTable);
        }

        return shadowTable;
    }

    /**
     * Drops the shadow table if it exists.
     */
    public void dropShadowTable(String sourceTable) throws SQLException {
        String shadowTable = getShadowTableName(sourceTable);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            log.info("Dropped shadow table: {}", shadowTable);
        }
    }
}
