package com.safemigrate.core.cutover;

import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.state.StateStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * Scale Benchmark Runner:
 * Executes high-volume synthetic stress migration across 100,000 to 10,000,000 rows.
 * Measures seeding throughput, backfill ingestion rates, memory footprints, and cleanup.
 */
public class TenMillionBenchmarkRunner {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String REDIS_URL = "redis://localhost:6380";

    public static void main(String[] args) throws Exception {
        int testRows = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
        int batchSize = args.length > 1 ? Integer.parseInt(args[1]) : 10000;

        System.out.println("===============================================================");
        System.out.println("STARTING SCALE BENCHMARK FOR " + testRows + " ROWS (Batch: " + batchSize + ")");
        System.out.println("===============================================================");

        String sourceTable = "bench_orders_" + System.currentTimeMillis();
        String shadowTable = sourceTable + "__shadow";
        String migrationId = "bench-" + UUID.randomUUID();

        StateStore stateStore = new StateStore(REDIS_URL);

        try (Connection conn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
             Statement stmt = conn.createStatement()) {

            // 1. Create Source Table
            System.out.println("[1/4] Creating source table '" + sourceTable + "'...");
            stmt.execute("CREATE TABLE " + sourceTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ");");

            // 2. Seed Rows via generate_series
            System.out.println("[2/4] Seeding " + testRows + " rows via generate_series...");
            long seedStart = System.currentTimeMillis();
            stmt.execute("INSERT INTO " + sourceTable + " (customer_id, amount, status) " +
                    "SELECT 'cust_' || (g % 10000), (g * 1.75)::numeric(10,2), 'COMPLETED' " +
                    "FROM generate_series(1, " + testRows + ") g;");
            long seedDuration = System.currentTimeMillis() - seedStart;
            System.out.println("Seeding completed in " + seedDuration + " ms (" + (testRows * 1000L / Math.max(1, seedDuration)) + " rows/sec)");

            // 3. Create Shadow Table with modified schema
            System.out.println("[3/4] Creating shadow table with schema addition...");
            ShadowTableManager shadowManager = new ShadowTableManager(conn);
            shadowManager.createShadowTable(sourceTable, "ADD COLUMN priority_score INT DEFAULT 42, ADD COLUMN is_verified BOOLEAN DEFAULT true");

            // 4. Run Backfill
            System.out.println("[4/4] Executing BackfillWorker...");
            List<String> cols = List.of("id", "customer_id", "amount", "status", "updated_at");
            long backfillStart = System.currentTimeMillis();

            try (BackfillWorker worker = new BackfillWorker(
                    conn, stateStore, migrationId, sourceTable, shadowTable, "id", cols, batchSize, 0
            )) {
                worker.runBackfill();
            }

            long backfillDuration = System.currentTimeMillis() - backfillStart;
            long rowsCopied = stateStore.getRowsBackfilled(migrationId);
            double throughput = (rowsCopied * 1000.0) / Math.max(1, backfillDuration);

            System.out.println("===============================================================");
            System.out.println("BENCHMARK RESULTS FOR " + rowsCopied + " ROWS:");
            System.out.println("Seeding Time    : " + seedDuration + " ms");
            System.out.println("Backfill Time   : " + backfillDuration + " ms (" + String.format("%.2f", backfillDuration / 1000.0) + " s)");
            System.out.println("Throughput      : " + String.format("%.0f", throughput) + " rows/sec");
            System.out.println("Total Time      : " + (seedDuration + backfillDuration) + " ms");
            System.out.println("===============================================================");

            // Cleanup
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + sourceTable + " CASCADE;");
        } finally {
            stateStore.close();
        }
    }
}
