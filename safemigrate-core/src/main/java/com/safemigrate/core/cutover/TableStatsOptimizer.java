package com.safemigrate.core.cutover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Production Hardening: Query Planner Statistics Optimizer.
 *
 * In PostgreSQL, newly populated shadow tables have no catalog planner statistics (reltuples = 0)
 * until autovacuum or manual ANALYZE executes.
 *
 * If cutover occurs without warmed statistics:
 * 1. The PostgreSQL optimizer assumes the table has 0 or few rows.
 * 2. It chooses disastrous full-table sequential scans over index scans on incoming production queries.
 * 3. Database CPU spikes to 100%, causing a self-inflicted post-migration outage!
 *
 * TableStatsOptimizer runs a targeted ANALYZE on the shadow table immediately prior
 * to atomic cutover, ensuring production queries hit primed index plans from millisecond 1.
 */
public class TableStatsOptimizer {

    private static final Logger log = LoggerFactory.getLogger(TableStatsOptimizer.class);

    private final Connection connection;

    public TableStatsOptimizer(Connection connection) {
        this.connection = connection;
    }

    /**
     * Executes ANALYZE on the shadow table and returns estimated row count from pg_class.
     */
    public long warmPlannerStatistics(String tableName) throws SQLException {
        log.info("Warming PostgreSQL query planner statistics via ANALYZE on '{}'...", tableName);
        long start = System.currentTimeMillis();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(String.format("ANALYZE \"%s\";", tableName));
        }

        long duration = System.currentTimeMillis() - start;
        long estimatedTuples = getEstimatedRowCount(tableName);

        log.info("Planner statistics primed for '{}' in {} ms (Estimated reltuples: {})",
                tableName, duration, estimatedTuples);
        return estimatedTuples;
    }

    /**
     * Queries pg_class to read the planner's reltuples estimate.
     */
    public long getEstimatedRowCount(String tableName) throws SQLException {
        String sql = "SELECT reltuples FROM pg_class WHERE relname = ? AND relnamespace = current_schema()::regnamespace";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return (long) rs.getDouble("reltuples");
                }
            }
        }
        return 0L;
    }
}
