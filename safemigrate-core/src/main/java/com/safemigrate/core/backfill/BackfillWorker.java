package com.safemigrate.core.backfill;

import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies existing historical records from the source table to the shadow table
 * in primary-key ordered, throttled batches with crash-safe checkpointing in Redis.
 */
public class BackfillWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackfillWorker.class);

    private final Connection connection;
    private final StateStore stateStore;
    private final String migrationId;
    private final String sourceTable;
    private final String shadowTable;
    private final String pkColumn;
    private final List<String> columnsToCopy;
    private final int batchSize;
    private final long throttleDelayMs;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackfillWorker(Connection connection,
                          StateStore stateStore,
                          String migrationId,
                          String sourceTable,
                          String shadowTable,
                          String pkColumn,
                          List<String> columnsToCopy,
                          int batchSize,
                          long throttleDelayMs) {
        this.connection = connection;
        this.stateStore = stateStore;
        this.migrationId = migrationId;
        this.sourceTable = sourceTable;
        this.shadowTable = shadowTable;
        this.pkColumn = pkColumn;
        this.columnsToCopy = columnsToCopy;
        this.batchSize = batchSize;
        this.throttleDelayMs = throttleDelayMs;
    }

    /**
     * Executes the backfill process synchronously or in the calling thread until complete.
     */
    public void runBackfill() throws SQLException, InterruptedException {
        running.set(true);
        stateStore.setStatus(migrationId, MigrationState.BACKFILLING);

        // Resume from last checkpoint if worker crashed previously
        Long checkpointPk = stateStore.getLastCopiedPk(migrationId);
        long currentPk = (checkpointPk != null) ? checkpointPk : 0L;
        long totalRowsCopied = stateStore.getRowsBackfilled(migrationId);

        log.info("Starting backfill for table '{}' -> '{}' starting at PK > {}, batchSize={}, throttle={}ms",
                sourceTable, shadowTable, currentPk, batchSize, throttleDelayMs);

        String selectSql = buildSelectBatchSql();
        String insertSql = buildInsertBatchSql();

        long startTime = System.currentTimeMillis();

        try (PreparedStatement selectStmt = connection.prepareStatement(selectSql);
             PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {

            while (running.get()) {
                selectStmt.setLong(1, currentPk);
                selectStmt.setInt(2, batchSize);

                int batchRows = 0;
                long maxPkInBatch = currentPk;

                try (ResultSet rs = selectStmt.executeQuery()) {
                    while (rs.next()) {
                        batchRows++;
                        maxPkInBatch = rs.getLong(pkColumn);

                        // Bind all column values from source to shadow
                        for (int i = 0; i < columnsToCopy.size(); i++) {
                            insertStmt.setObject(i + 1, rs.getObject(i + 1));
                        }
                        insertStmt.addBatch();
                    }
                }

                if (batchRows == 0) {
                    // All existing rows have been copied!
                    log.info("Backfill complete! Copied {} total rows in {} ms.",
                            totalRowsCopied, System.currentTimeMillis() - startTime);
                    stateStore.setStatus(migrationId, MigrationState.CATCHING_UP);
                    break;
                }

                // Execute the batch insert with ON CONFLICT DO NOTHING
                insertStmt.executeBatch();
                totalRowsCopied += batchRows;
                currentPk = maxPkInBatch;

                // Checkpoint progress to Redis
                stateStore.checkpointBackfill(migrationId, currentPk, totalRowsCopied);

                log.info("Backfilled batch of {} rows (Total: {} | Last PK: {})",
                        batchRows, totalRowsCopied, currentPk);

                // Throttling to prevent spiking DB CPU or IOPS
                if (throttleDelayMs > 0) {
                    Thread.sleep(throttleDelayMs);
                }

                // If last batch had fewer than batchSize rows, we've reached the end of the historical snapshot
                if (batchRows < batchSize) {
                    log.info("Final backfill batch finished. Total rows: {}", totalRowsCopied);
                    stateStore.setStatus(migrationId, MigrationState.CATCHING_UP);
                    break;
                }
            }
        }
    }

    private String buildSelectBatchSql() {
        String cols = String.join(", ", columnsToCopy);
        return String.format("SELECT %s FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?",
                cols, sourceTable, pkColumn, pkColumn);
    }

    private String buildInsertBatchSql() {
        String cols = String.join(", ", columnsToCopy);
        String placeholders = String.join(", ", columnsToCopy.stream().map(c -> "?").toList());
        // ON CONFLICT DO NOTHING: guarantees historical backfill NEVER overwrites live WAL writes!
        return String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO NOTHING",
                shadowTable, cols, placeholders, pkColumn);
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void close() {
        stop();
    }
}
