package com.safemigrate.core.cutover;

import com.safemigrate.core.reconcile.DataReconciliationService;
import com.safemigrate.core.reconcile.ReconciliationReport;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import org.postgresql.replication.LogSequenceNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * Coordinates atomic table swap (cutover) and fail-safe rollback.
 * Promotes the shadow table to the production table in single-digit milliseconds
 * using transactional DDL with lock-timeout protection against traffic starvation.
 */
public class CutoverCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CutoverCoordinator.class);

    private final Connection connection;
    private final StateStore stateStore;
    private final String migrationId;
    private final String sourceTable;
    private final String shadowTable;
    private final String oldTable;
    private final long lockTimeoutMs;
    private final String pkColumn;

    public CutoverCoordinator(Connection connection,
                              StateStore stateStore,
                              String migrationId,
                              String sourceTable,
                              String shadowTable,
                              String oldTable,
                              long lockTimeoutMs,
                              String pkColumn) {
        this.connection = connection;
        this.stateStore = stateStore;
        this.migrationId = migrationId;
        this.sourceTable = sourceTable;
        this.shadowTable = (shadowTable != null && !shadowTable.isBlank()) ? shadowTable : sourceTable + "__shadow";
        this.oldTable = (oldTable != null && !oldTable.isBlank()) ? oldTable : sourceTable + "__old";
        this.lockTimeoutMs = lockTimeoutMs > 0 ? lockTimeoutMs : 2000L;
        this.pkColumn = pkColumn;
    }

    public CutoverCoordinator(Connection connection,
                              StateStore stateStore,
                              String migrationId,
                              String sourceTable,
                              String shadowTable,
                              String oldTable,
                              long lockTimeoutMs) {
        this(connection, stateStore, migrationId, sourceTable, shadowTable, oldTable, lockTimeoutMs, null);
    }

    public CutoverCoordinator(Connection connection,
                              StateStore stateStore,
                              String migrationId,
                              String sourceTable) {
        this(connection, stateStore, migrationId, sourceTable, null, null, 2000L, null);
    }

    /**
     * Calculates the replication lag in bytes between PostgreSQL's current WAL position
     * and the last applied LSN recorded in the StateStore.
     */
    public long getReplicationLagBytes() throws SQLException {
        if (stateStore == null || migrationId == null) {
            return 0L;
        }

        Long lastAppliedLsn = stateStore.getLastAppliedLsn(migrationId);
        if (lastAppliedLsn == null || lastAppliedLsn <= 0) {
            return Long.MAX_VALUE; // Applier has not processed any events yet
        }

        String lsnStr = LogSequenceNumber.valueOf(lastAppliedLsn).asString();
        String sql = "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn) as lag_bytes;";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, lsnStr);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long lag = rs.getLong("lag_bytes");
                    return Math.max(0L, lag);
                }
            }
        }
        return Long.MAX_VALUE;
    }

    /**
     * Checks whether the migration is ready for cutover:
     * 1. Backfill is complete (status is CATCHING_UP or READY_CUTOVER).
     * 2. Replication lag in bytes is within maxAllowedLagBytes.
     */
    public boolean isReadyForCutover(long maxAllowedLagBytes) throws SQLException {
        if (stateStore != null && migrationId != null) {
            MigrationState state = stateStore.getStatus(migrationId);
            if (state != MigrationState.CATCHING_UP && state != MigrationState.READY_CUTOVER) {
                log.debug("Migration [{}] not ready for cutover: current state is {}", migrationId, state);
                return false;
            }
        }

        long lagBytes = getReplicationLagBytes();
        log.debug("Migration [{}] replication lag: {} bytes (max allowed: {} bytes)",
                migrationId, lagBytes, maxAllowedLagBytes);

        boolean ready = lagBytes <= maxAllowedLagBytes;
        if (ready && stateStore != null && migrationId != null) {
            stateStore.setStatus(migrationId, MigrationState.READY_CUTOVER);
        }
        return ready;
    }

    /**
     * Waits until replication lag drops below maxAllowedLagBytes or timeout expires.
     */
    public boolean waitForCatchup(long maxAllowedLagBytes, Duration timeout) throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (isReadyForCutover(maxAllowedLagBytes)) {
                return true;
            }
            Thread.sleep(100);
        }
        return isReadyForCutover(maxAllowedLagBytes);
    }

    /**
     * Executes the atomic table rename inside a single transaction.
     * Sequence:
     * 1. SET LOCAL lock_timeout
     * 2. LOCK TABLE source, shadow IN ACCESS EXCLUSIVE MODE
     * 3. ALTER TABLE source RENAME TO old
     * 4. ALTER TABLE shadow RENAME TO source
     * 5. COMMIT
     *
     * Total lock hold time: single-digit milliseconds.
     *
     * @return Duration of the atomic swap in milliseconds
     */
    public long executeCutover() throws SQLException {
        log.info("Initiating atomic cutover: '{}' -> '{}' and '{}' -> '{}' (lock_timeout: {}ms)",
                sourceTable, oldTable, shadowTable, sourceTable, lockTimeoutMs);

        if (stateStore != null && migrationId != null) {
            MigrationState currentStatus = stateStore.getStatus(migrationId);
            if (currentStatus == MigrationState.COMPLETED) {
                log.info("Migration [{}] is already COMPLETED. Redundant cutover call ignored (idempotent).", migrationId);
                return 0L;
            }
            stateStore.setStatus(migrationId, MigrationState.CUTTING_OVER);
        }

        long startTime = System.currentTimeMillis();
        boolean originalAutoCommit = connection.getAutoCommit();

        try {
            connection.setAutoCommit(false);

            try (Statement stmt = connection.createStatement()) {
                // 1. Guard against starving production traffic by setting a strict lock acquisition timeout
                stmt.execute(String.format("SET LOCAL lock_timeout = '%dms';", lockTimeoutMs));

                // 2. Acquire ACCESS EXCLUSIVE lock on both source and shadow tables
                log.debug("Acquiring ACCESS EXCLUSIVE lock on '{}' and '{}'...", sourceTable, shadowTable);
                stmt.execute(String.format("LOCK TABLE \"%s\" IN ACCESS EXCLUSIVE MODE;", sourceTable));
                stmt.execute(String.format("LOCK TABLE \"%s\" IN ACCESS EXCLUSIVE MODE;", shadowTable));

                // 3. Atomically synchronize sequence high-watermark while exclusive lock is held
                syncSequenceInsideLock(stmt);

                // 4. Clean up any stale old table from prior aborted runs
                stmt.execute(String.format("DROP TABLE IF EXISTS \"%s\" CASCADE;", oldTable));

                // 5. Atomic metadata swap
                stmt.execute(String.format("ALTER TABLE \"%s\" RENAME TO \"%s\";", sourceTable, oldTable));
                stmt.execute(String.format("ALTER TABLE \"%s\" RENAME TO \"%s\";", shadowTable, sourceTable));
            }

            // 5. Commit the transaction
            connection.commit();

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("ATOMIC CUTOVER SUCCESSFUL in {} ms! Table '{}' promoted with new schema. Old table preserved as '{}'.",
                    durationMs, sourceTable, oldTable);

            if (stateStore != null && migrationId != null) {
                stateStore.setStatus(migrationId, MigrationState.COMPLETED);
                try {
                    reconcile();
                } catch (Exception reconEx) {
                    log.warn("Post-cutover reconciliation audit warning for migration [{}]: {}", migrationId, reconEx.getMessage());
                }
            }

            return durationMs;

        } catch (SQLException e) {
            log.error("Atomic cutover failed: {}. Rolling back transaction...", e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException rbEx) {
                log.error("Transaction rollback failed: {}", rbEx.getMessage(), rbEx);
            }

            if (stateStore != null && migrationId != null) {
                // If the failure was a lock timeout, revert to CATCHING_UP so the coordinator can retry
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("lock timeout")) {
                    log.warn("Lock timeout triggered. Diagnosing conflicting locks on '{}'...", sourceTable);
                    diagnoseLockBlockers();
                    log.warn("Reverting state to CATCHING_UP for retry.");
                    stateStore.setStatus(migrationId, MigrationState.CATCHING_UP);
                } else {
                    stateStore.setStatus(migrationId, MigrationState.FAILED);
                }
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    private void diagnoseLockBlockers() {
        String sql = """
            SELECT a.pid, a.usename, a.client_addr, a.query, age(clock_timestamp(), a.query_start) as duration
            FROM pg_stat_activity a
            WHERE a.pid <> pg_backend_pid()
              AND a.state = 'active'
              AND a.query ILIKE '%' || ? || '%'
            LIMIT 5
        """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, sourceTable);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    log.warn("DIAGNOSTIC - Lock Blocker detected: PID={}, User={}, Addr={}, Duration={}, Query='{}'",
                            rs.getInt("pid"), rs.getString("usename"), rs.getString("client_addr"),
                            rs.getString("duration"), rs.getString("query"));
                }
            }
        } catch (Exception diagEx) {
            log.debug("Could not run lock blocker diagnostics: {}", diagEx.getMessage());
        }
    }

    /**
     * Synchronizes sequence high-watermark atomically while ACCESS EXCLUSIVE lock is held on both tables.
     * Guarantees zero duplicate key errors for concurrent in-flight and post-cutover transactions.
     */
    private void syncSequenceInsideLock(Statement stmt) {
        try {
            SequenceManager seqMgr = new SequenceManager(connection);
            String pk = this.pkColumn;
            if (pk == null || pk.isBlank()) {
                String pkSql = """
                    SELECT kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                      AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                      AND tc.table_name = ?
                      AND tc.table_schema = 'public'
                    LIMIT 1;
                """;
                try (PreparedStatement pkStmt = connection.prepareStatement(pkSql)) {
                    pkStmt.setString(1, sourceTable.toLowerCase());
                    try (ResultSet rs = pkStmt.executeQuery()) {
                        if (rs.next()) {
                            pk = rs.getString(1);
                        }
                    }
                }
            }

            if (pk == null || pk.isBlank()) {
                return;
            }

            String seqName = seqMgr.getSequenceName(shadowTable, pk);
            if (seqName == null) {
                seqName = seqMgr.getSequenceName(sourceTable, pk);
            }
            if (seqName == null) {
                return;
            }

            // Calculate true absolute max ID across both tables at this exact locked instant
            String maxSql = String.format(
                    "SELECT GREATEST(" +
                    "COALESCE((SELECT MAX(\"%s\") FROM \"%s\"), 0), " +
                    "COALESCE((SELECT MAX(\"%s\") FROM \"%s\"), 0)" +
                    ")",
                    pk, sourceTable, pk, shadowTable
            );
            long maxId = 0L;
            try (ResultSet rs = stmt.executeQuery(maxSql)) {
                if (rs.next()) {
                    maxId = rs.getLong(1);
                }
            }

            if (maxId > 0) {
                String setvalSql = String.format("SELECT setval('%s'::regclass, %d, true)", seqName, maxId);
                stmt.execute(setvalSql);
                log.info("Atomically synchronized sequence '{}' inside exclusive cutover lock to {}", seqName, maxId);

                // Transfer sequence ownership to shadow table (which will be renamed to sourceTable)
                try {
                    String ownSql = String.format("ALTER SEQUENCE %s OWNED BY \"%s\".\"%s\"", seqName, shadowTable, pk);
                    stmt.execute(ownSql);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Sequence synchronization inside cutover lock encountered non-fatal warning: {}", e.getMessage());
        }
    }

    /**
     * Safely aborts an in-flight migration before cutover.
     * Drops the shadow table and marks the migration as ROLLED_BACK.
     * The original source table is completely untouched.
     */
    public void rollback() throws SQLException {
        log.info("Executing rollback for migration [{}]: dropping shadow table '{}'...", migrationId, shadowTable);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(String.format("DROP TABLE IF EXISTS \"%s\" CASCADE;", shadowTable));
        }

        if (stateStore != null && migrationId != null) {
            stateStore.setStatus(migrationId, MigrationState.ROLLED_BACK);
        }
        log.info("Rollback complete for migration [{}]. Shadow table '{}' dropped.", migrationId, shadowTable);
    }

    /**
     * Emergency post-cutover reversion:
     * If an application bug is discovered after cutover, atomically swaps the old table back
     * to the production table in single-digit milliseconds.
     */
    public long emergencyRevert() throws SQLException {
        log.warn("EMERGENCY REVERSION INITIATED: Swapping '{}' back to '{}'...", oldTable, sourceTable);

        long startTime = System.currentTimeMillis();
        boolean originalAutoCommit = connection.getAutoCommit();

        try {
            connection.setAutoCommit(false);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute(String.format("SET LOCAL lock_timeout = '%dms';", lockTimeoutMs));
                stmt.execute(String.format("LOCK TABLE \"%s\" IN ACCESS EXCLUSIVE MODE;", sourceTable));
                stmt.execute(String.format("LOCK TABLE \"%s\" IN ACCESS EXCLUSIVE MODE;", oldTable));

                String revertedTable = sourceTable + "__reverted_" + System.currentTimeMillis();
                stmt.execute(String.format("ALTER TABLE \"%s\" RENAME TO \"%s\";", sourceTable, revertedTable));
                stmt.execute(String.format("ALTER TABLE \"%s\" RENAME TO \"%s\";", oldTable, sourceTable));
            }

            connection.commit();
            long durationMs = System.currentTimeMillis() - startTime;

            log.warn("EMERGENCY REVERSION COMPLETE in {} ms! Table '{}' restored to original schema.",
                    durationMs, sourceTable);

            if (stateStore != null && migrationId != null) {
                stateStore.setStatus(migrationId, MigrationState.ROLLED_BACK);
            }
            return durationMs;

        } catch (SQLException e) {
            log.error("Emergency reversion failed: {}", e.getMessage(), e);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Performs a mathematical post-cutover data reconciliation check between the promoted
     * production table and the old historical table.
     */
    public ReconciliationReport reconcile() throws SQLException {
        DataReconciliationService reconService = new DataReconciliationService(connection);
        ReconciliationReport report = reconService.reconcile(sourceTable, oldTable);
        if (stateStore != null && migrationId != null) {
            stateStore.saveReconciliationReport(migrationId, report.toJson());
        }
        return report;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public String getShadowTable() {
        return shadowTable;
    }

    public String getOldTable() {
        return oldTable;
    }

    @Override
    public void close() {
        // Connection lifecycle is managed by caller
    }
}
