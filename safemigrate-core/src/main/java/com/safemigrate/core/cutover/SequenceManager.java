package com.safemigrate.core.cutover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Production Hardening: Sequence & Auto-Increment Ownership Manager.
 *
 * In PostgreSQL, table cloning (LIKE source INCLUDING ALL) copies default expressions
 * such as nextval('source_id_seq'::regclass).
 *
 * During zero-downtime cutover, two critical production sequence bugs can occur:
 * 1. Sequence Stale Watermark: If rows were backfilled with explicit IDs higher than
 *    the sequence counter, the very first post-cutover INSERT will trigger a duplicate key
 *    Primary Key violation!
 * 2. Sequence Ownership Skew: The sequence must be owned by the promoted production table
 *    so future table maintenance or drops don't cascade or orphan the sequence.
 *
 * SequenceManager guarantees seamless post-cutover writes by syncing high-watermarks
 * and transferring sequence ownership.
 */
public class SequenceManager {

    private static final Logger log = LoggerFactory.getLogger(SequenceManager.class);

    private final Connection connection;

    public SequenceManager(Connection connection) {
        this.connection = connection;
    }

    /**
     * Resolves the sequence name associated with a table's primary key or serial column.
     */
    public String getSequenceName(String tableName, String columnName) throws SQLException {
        // 1. Try standard pg_get_serial_sequence
        String sql = "SELECT pg_get_serial_sequence(?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            stmt.setString(2, columnName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String seq = rs.getString(1);
                    if (seq != null) return seq;
                }
            }
        }

        // 2. Fallback: Extract from column_default (e.g. nextval('orders_id_seq'::regclass))
        String defaultSql = """
            SELECT column_default
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = ?
              AND column_name = ?
        """;
        try (PreparedStatement stmt = connection.prepareStatement(defaultSql)) {
            stmt.setString(1, tableName.toLowerCase());
            stmt.setString(2, columnName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String colDefault = rs.getString("column_default");
                    if (colDefault != null && colDefault.contains("nextval(")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([^']+)'").matcher(colDefault);
                        if (m.find()) {
                            return m.group(1);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Synchronizes the sequence counter so that nextval() produces values strictly greater
     * than the maximum ID currently present in the promoted table.
     */
    public long syncSequenceHighWatermark(String tableName, String columnName) throws SQLException {
        String seqName = getSequenceName(tableName, columnName);
        if (seqName == null) {
            log.debug("No sequence associated with column '{}.{}'. Skipping sequence sync.", tableName, columnName);
            return 0L;
        }

        // Get max ID from the table
        String maxSql = String.format("SELECT COALESCE(MAX(\"%s\"), 0) FROM \"%s\"", columnName, tableName);
        long maxId = 0L;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(maxSql)) {
            if (rs.next()) {
                maxId = rs.getLong(1);
            }
        }

        if (maxId > 0) {
            String setvalSql = "SELECT setval(?::regclass, ?, true)";
            try (PreparedStatement stmt = connection.prepareStatement(setvalSql)) {
                stmt.setString(1, seqName);
                stmt.setLong(2, maxId);
                stmt.executeQuery();
            }
            log.info("Synchronized sequence '{}' high-watermark to {}", seqName, maxId);

            // Re-assign sequence ownership to the newly promoted table
            try {
                transferOwnership(seqName, tableName, columnName);
            } catch (Exception e) {
                log.debug("Could not transfer sequence ownership for {}: {}", seqName, e.getMessage());
            }
        }

        return maxId;
    }

    /**
     * Re-assigns sequence ownership to the newly promoted production table.
     */
    public void transferOwnership(String sequenceName, String tableName, String columnName) throws SQLException {
        if (sequenceName == null || sequenceName.isBlank()) return;

        String sql = String.format("ALTER SEQUENCE %s OWNED BY \"%s\".\"%s\"",
                sequenceName, tableName, columnName);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            log.info("Transferred sequence ownership: {} -> {}.{}", sequenceName, tableName, columnName);
        }
    }
}
