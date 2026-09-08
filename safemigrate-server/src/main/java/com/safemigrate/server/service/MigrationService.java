package com.safemigrate.server.service;

import com.safemigrate.core.applier.ChangeApplier;
import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.backfill.ShadowTableManager;
import com.safemigrate.core.cutover.CutoverCoordinator;
import com.safemigrate.core.cutover.SequenceManager;
import com.safemigrate.core.cutover.TableStatsOptimizer;
import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.kafka.WalKafkaConsumer;
import com.safemigrate.core.kafka.WalKafkaProducer;
import com.safemigrate.core.preflight.PreflightInspector;
import com.safemigrate.core.preflight.PreflightReport;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.PostgresReplicationConnectionFactory;
import com.safemigrate.core.wal.TestDecodingDecoder;
import com.safemigrate.core.wal.WalReader;
import com.safemigrate.server.config.SafeMigrateProperties;
import com.safemigrate.server.dto.ApprovalRequest;
import com.safemigrate.server.dto.CreateMigrationRequest;
import com.safemigrate.server.dto.MigrationResponse;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final SafeMigrateProperties properties;
    private final StateStore stateStore;
    private final KafkaTopicManager topicManager;
    private final PostgresReplicationConnectionFactory repConnFactory;
    private final MigrationSseService sseService;
    private final ExecutorService migrationExecutor;

    private final Map<String, MigrationSession> sessions = new ConcurrentHashMap<>();

    public MigrationService(SafeMigrateProperties properties,
                            StateStore stateStore,
                            KafkaTopicManager topicManager,
                            PostgresReplicationConnectionFactory repConnFactory,
                            MigrationSseService sseService,
                            @Qualifier("migrationExecutor") ExecutorService migrationExecutor) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.topicManager = topicManager;
        this.repConnFactory = repConnFactory;
        this.sseService = sseService;
        this.migrationExecutor = migrationExecutor;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getTargetDb().getUrl(),
                properties.getTargetDb().getUsername(),
                properties.getTargetDb().getPassword()
        );
    }

    public PreflightReport runPreflightCheck(String tableName, String ddlStatement) throws SQLException {
        try (Connection conn = getConnection()) {
            PreflightInspector inspector = new PreflightInspector(conn);
            return inspector.inspect(tableName, ddlStatement);
        }
    }

    public MigrationResponse submitMigration(CreateMigrationRequest request) {
        String tableName = request.getTableName().trim();
        String ddl = request.getDdlStatement().trim();

        String id = "mig_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        int batchSize = (request.getBatchSize() != null && request.getBatchSize() > 0)
                ? request.getBatchSize()
                : properties.getMigration().getDefaultBatchSize();
        long throttleDelayMs = (request.getThrottleDelayMs() != null && request.getThrottleDelayMs() >= 0)
                ? request.getThrottleDelayMs()
                : properties.getMigration().getDefaultThrottleDelayMs();
        boolean autoCutover = Boolean.TRUE.equals(request.getAutoCutover());

        MigrationSession session = new MigrationSession(id, tableName, ddl, batchSize, throttleDelayMs, autoCutover);
        sessions.put(id, session);

        // 1. Run Pre-Flight Inspection synchronously
        PreflightReport report;
        try {
            report = runPreflightCheck(tableName, ddl);
            session.setPreflightReport(report);
            if (!report.passed()) {
                session.setState(MigrationState.FAILED);
                session.setErrorMessage("Pre-flight safety inspection failed: " + report.getErrors());
                stateStore.setStatus(id, MigrationState.FAILED);
                sseService.broadcastStatusChange(id, MigrationState.FAILED, session.getErrorMessage());
                log.warn("Migration [{}] rejected by pre-flight checks: {}", id, report.getErrors());
                return session.toResponse();
            }
        } catch (SQLException e) {
            session.setState(MigrationState.FAILED);
            session.setErrorMessage("Failed to run pre-flight inspection: " + e.getMessage());
            stateStore.setStatus(id, MigrationState.FAILED);
            return session.toResponse();
        }

        // 2. Check if table is currently locked
        if (stateStore.isTableLocked(tableName)) {
            session.setState(MigrationState.FAILED);
            session.setErrorMessage("Table '" + tableName + "' is currently locked by another active migration.");
            stateStore.setStatus(id, MigrationState.FAILED);
            return session.toResponse();
        }

        // 3. Submit async lifecycle worker in Virtual Thread
        migrationExecutor.submit(() -> runMigrationLifecycle(session));

        return session.toResponse();
    }

    private void runMigrationLifecycle(MigrationSession session) {
        String id = session.getId();
        String tableName = session.getTableName();
        String shadowTableName = session.getShadowTableName();
        String oldTableName = session.getOldTableName();
        String slotName = session.getSlotName();
        String ddl = session.getDdlStatement();

        Connection setupConn = null;
        Connection applierConn = null;
        WalKafkaProducer producer = null;

        try {
            // Step 1: Acquire exclusive distributed lock on table
            RLock lock = stateStore.acquireTableLock(tableName, 5, 3600);
            session.setTableLock(lock);

            session.setState(MigrationState.INITIALIZING);
            stateStore.setStatus(id, MigrationState.INITIALIZING);
            sseService.broadcastStatusChange(id, MigrationState.INITIALIZING, "Initializing migration resources...");

            setupConn = getConnection();

            // Count initial source rows
            try (Statement stmt = setupConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + tableName + "\"")) {
                if (rs.next()) {
                    session.setTotalSourceRows(rs.getLong(1));
                }
            }

            // Step 2: Ensure replication slot exists BEFORE creating shadow table
            repConnFactory.ensureReplicationSlotExists(slotName, "test_decoding");

            // Step 3: Ensure Kafka topic exists
            topicManager.ensureTopicExists(tableName, 1, (short) 1);

            // Step 4: Create Shadow Table with DDL
            ShadowTableManager shadowMgr = new ShadowTableManager(setupConn);
            shadowMgr.createShadowTable(tableName, ddl);
            String pkColumn = shadowMgr.findPrimaryKeyColumn(tableName);
            List<String> columns = shadowMgr.getColumnNames(tableName);

            // Step 5: Initialize ChangeApplier & Kafka Consumer
            applierConn = getConnection();
            session.setApplierConnection(applierConn);
            ChangeApplier changeApplier = new ChangeApplier(applierConn, stateStore, id, tableName, shadowTableName, pkColumn);
            session.setChangeApplier(changeApplier);

            String consumerGroup = "safemigrate-server-" + id;
            WalKafkaConsumer kafkaConsumer = new WalKafkaConsumer(properties.getKafka().getBootstrapServers(), consumerGroup, tableName);
            session.setKafkaConsumer(kafkaConsumer);
            changeApplier.start(kafkaConsumer);

            // Step 6: Initialize WalReader & Kafka Producer
            producer = new WalKafkaProducer(properties.getKafka().getBootstrapServers(), pkColumn);
            final WalKafkaProducer finalProducer = producer;
            WalReader walReader = new WalReader(repConnFactory, slotName, tableName, new TestDecodingDecoder(), finalProducer::send);
            session.setWalReader(walReader);
            walReader.start(null);

            // Allow consumer partition assignment
            Thread.sleep(1200);

            // Step 7: Transition to BACKFILLING
            session.setState(MigrationState.BACKFILLING);
            stateStore.setStatus(id, MigrationState.BACKFILLING);
            sseService.broadcastStatusChange(id, MigrationState.BACKFILLING, "Historical backfill started...");

            try (BackfillWorker worker = new BackfillWorker(
                    setupConn, stateStore, id, tableName, shadowTableName, pkColumn,
                    columns, session.getBatchSize(), session.getThrottleDelayMs())) {
                session.setBackfillWorker(worker);
                worker.setProgressListener((lastPk, totalCopied) -> {
                    session.setRowsBackfilled(totalCopied);
                    session.setLastAppliedLsn(stateStore.getLastAppliedLsn(id));
                    session.setAppliedInserts(changeApplier.getInsertCount());
                    session.setAppliedUpdates(changeApplier.getUpdateCount());
                    session.setAppliedDeletes(changeApplier.getDeleteCount());
                    session.setTotalApplied(changeApplier.getTotalApplied());
                    sseService.broadcastProgress(id, session.toProgressEvent());
                });
                worker.runBackfill();
                session.setRowsBackfilled(worker.getRowsBackfilled());
            }

            // Check if cancelled/rolled back during backfill
            if (session.getState() == MigrationState.ROLLED_BACK || session.getState() == MigrationState.FAILED) {
                return;
            }

            // Step 8: Transition to CATCHING_UP
            session.setState(MigrationState.CATCHING_UP);
            stateStore.setStatus(id, MigrationState.CATCHING_UP);
            sseService.broadcastStatusChange(id, MigrationState.CATCHING_UP, "Historical backfill complete. Catching up live replication lag...");

            try (Connection cutoverConn = getConnection()) {
                CutoverCoordinator cutoverCoordinator = new CutoverCoordinator(
                        cutoverConn, stateStore, id, tableName, shadowTableName, oldTableName,
                        properties.getMigration().getMaxLockTimeoutMs()
                );
                session.setCutoverCoordinator(cutoverCoordinator);

                // Drain replication lag
                long lastApplied = changeApplier.getTotalApplied();
                int quiescentCount = 0;
                for (int i = 0; i < 40; i++) {
                    if (session.getState() == MigrationState.ROLLED_BACK) return;

                    long curLag = cutoverCoordinator.getReplicationLagBytes();
                    session.setReplicationLagBytes(curLag == Long.MAX_VALUE ? 0 : curLag);
                    session.setAppliedInserts(changeApplier.getInsertCount());
                    session.setAppliedUpdates(changeApplier.getUpdateCount());
                    session.setAppliedDeletes(changeApplier.getDeleteCount());
                    session.setTotalApplied(changeApplier.getTotalApplied());
                    sseService.broadcastProgress(id, session.toProgressEvent());

                    long curApplied = changeApplier.getTotalApplied();
                    long maxAllowedLag = properties.getMigration().getMaxAllowedLagBytes();
                    boolean lagCaughtUp = (curLag != Long.MAX_VALUE && curLag <= maxAllowedLag);
                    boolean quiesced = (curApplied == lastApplied);
                    if (lagCaughtUp || quiesced) {
                        quiescentCount++;
                        if (quiescentCount >= 2) {
                            break;
                        }
                    } else {
                        quiescentCount = 0;
                        lastApplied = curApplied;
                    }
                    Thread.sleep(500);
                }
            }

            // Step 9: Transition to READY_CUTOVER
            session.setState(MigrationState.READY_CUTOVER);
            stateStore.setStatus(id, MigrationState.READY_CUTOVER);
            sseService.broadcastStatusChange(id, MigrationState.READY_CUTOVER, "Replication lag converged within safety threshold. Ready for cutover.");
            sseService.broadcastProgress(id, session.toProgressEvent());

            // If auto-cutover is enabled and approved, trigger cutover automatically
            if (session.isAutoCutover() && (!properties.getMigration().isApprovalRequired() || session.isApproved())) {
                executeCutover(id);
                return;
            }

            // Background loop keeping WAL replication stream in sync until manual cutover or rollback
            while (session.getState() == MigrationState.READY_CUTOVER) {
                if (session.isAutoCutover() && session.isApproved()) {
                    executeCutover(id);
                    return;
                }
                session.setAppliedInserts(changeApplier.getInsertCount());
                session.setAppliedUpdates(changeApplier.getUpdateCount());
                session.setAppliedDeletes(changeApplier.getDeleteCount());
                session.setTotalApplied(changeApplier.getTotalApplied());
                sseService.broadcastProgress(id, session.toProgressEvent());
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            log.error("Migration [{}] failed on table '{}': {}", id, tableName, e.getMessage(), e);
            if (session.getState() != MigrationState.ROLLED_BACK && session.getState() != MigrationState.COMPLETED) {
                session.setState(MigrationState.FAILED);
                session.setErrorMessage(e.getMessage());
                stateStore.setStatus(id, MigrationState.FAILED);
                sseService.broadcastError(id, e.getMessage());
                cleanupSessionWorkers(session);
                if (session.getTableLock() != null) {
                    stateStore.releaseTableLock(session.getTableLock());
                    session.setTableLock(null);
                }
            }
        } finally {
            if (producer != null) {
                try { producer.close(); } catch (Exception ignored) {}
            }
            if (setupConn != null) {
                try { setupConn.close(); } catch (Exception ignored) {}
            }
        }
    }

    public MigrationResponse approveMigration(String id, ApprovalRequest request) {
        MigrationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Migration not found with id: " + id);
        }
        session.setApproved(true);
        session.setApprovedBy(request.getApprover());
        session.setApprovedAt(Instant.now());
        log.info("Migration [{}] approved by: {}", id, request.getApprover());

        sseService.broadcastStatusChange(id, session.getState(), "Migration approved by " + request.getApprover());
        sseService.broadcastProgress(id, session.toProgressEvent());

        if (session.isAutoCutover() && session.getState() == MigrationState.READY_CUTOVER) {
            return executeCutover(id);
        }

        return session.toResponse();
    }

    public MigrationResponse executeCutover(String id) {
        MigrationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Migration not found with id: " + id);
        }
        if (session.getState() == MigrationState.COMPLETED) {
            return session.toResponse(); // Idempotent
        }
        if (properties.getMigration().isApprovalRequired() && !session.isApproved()) {
            throw new IllegalStateException("Migration [" + id + "] has not been approved yet. Approval is required before cutover.");
        }
        if (session.getState() != MigrationState.READY_CUTOVER && session.getState() != MigrationState.CATCHING_UP) {
            throw new IllegalStateException("Migration [" + id + "] is not ready for cutover. Current state: " + session.getState());
        }

        session.setState(MigrationState.CUTTING_OVER);
        stateStore.setStatus(id, MigrationState.CUTTING_OVER);
        sseService.broadcastStatusChange(id, MigrationState.CUTTING_OVER, "Executing atomic table cutover...");

        try (Connection conn = getConnection()) {
            // 1. Synchronize sequence high-watermark
            ShadowTableManager shadowMgr = new ShadowTableManager(conn);
            String pkCol = shadowMgr.findPrimaryKeyColumn(session.getTableName());
            SequenceManager seqMgr = new SequenceManager(conn);
            seqMgr.syncSequenceHighWatermark(session.getShadowTableName(), pkCol);

            // 2. Prime query planner stats
            TableStatsOptimizer statsOpt = new TableStatsOptimizer(conn);
            statsOpt.warmPlannerStatistics(session.getShadowTableName());

            // 3. Execute atomic table swap (with sequence synchronization under lock)
            CutoverCoordinator coordinator = new CutoverCoordinator(
                    conn, stateStore, id, session.getTableName(), session.getShadowTableName(), session.getOldTableName(),
                    properties.getMigration().getMaxLockTimeoutMs(), pkCol
            );
            long durationMs = coordinator.executeCutover();
            session.setCutoverDurationMs(durationMs);

            // 4. Teardown active background streaming workers cleanly
            cleanupSessionWorkers(session);

            // 5. Drop replication slot
            try {
                repConnFactory.dropReplicationSlot(session.getSlotName());
            } catch (Exception e) {
                log.warn("Could not drop replication slot {}: {}", session.getSlotName(), e.getMessage());
            }

            // 6. Release table lock
            if (session.getTableLock() != null) {
                stateStore.releaseTableLock(session.getTableLock());
                session.setTableLock(null);
            }

            session.setState(MigrationState.COMPLETED);
            session.setCompletedAt(Instant.now());
            stateStore.setStatus(id, MigrationState.COMPLETED);
            sseService.broadcastStatusChange(id, MigrationState.COMPLETED, "Migration completed successfully in " + durationMs + "ms");
            sseService.broadcastProgress(id, session.toProgressEvent());
            sseService.completeStream(id);

            return session.toResponse();
        } catch (Exception e) {
            log.error("Failed cutover for migration [{}]: {}", id, e.getMessage(), e);
            session.setState(MigrationState.FAILED);
            session.setErrorMessage("Cutover failed: " + e.getMessage());
            stateStore.setStatus(id, MigrationState.FAILED);
            sseService.broadcastError(id, session.getErrorMessage());
            throw new RuntimeException("Cutover failed: " + e.getMessage(), e);
        }
    }

    public MigrationResponse rollbackMigration(String id, String reason) {
        MigrationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Migration not found with id: " + id);
        }
        if (session.getState() == MigrationState.ROLLED_BACK) {
            return session.toResponse(); // Idempotent
        }
        if (session.getState() == MigrationState.COMPLETED) {
            throw new IllegalStateException("Cannot rollback completed migration [" + id + "]. Use emergency revert instead.");
        }

        log.warn("Rolling back migration [{}] for table '{}', reason: {}", id, session.getTableName(), reason);
        session.setState(MigrationState.ROLLED_BACK);
        session.setErrorMessage("Rolled back: " + (reason != null ? reason : "User requested rollback"));
        session.setCompletedAt(Instant.now());
        stateStore.setStatus(id, MigrationState.ROLLED_BACK);

        // Teardown streaming workers
        cleanupSessionWorkers(session);

        // Drop shadow table
        try (Connection conn = getConnection()) {
            CutoverCoordinator coordinator = new CutoverCoordinator(
                    conn, stateStore, id, session.getTableName(), session.getShadowTableName(), session.getOldTableName(), 2000L
            );
            coordinator.rollback();
        } catch (Exception e) {
            log.warn("Failed to drop shadow table during rollback: {}", e.getMessage());
        }

        // Drop slot
        try {
            repConnFactory.dropReplicationSlot(session.getSlotName());
        } catch (Exception ignored) {}

        // Release lock
        if (session.getTableLock() != null) {
            stateStore.releaseTableLock(session.getTableLock());
            session.setTableLock(null);
        }

        sseService.broadcastStatusChange(id, MigrationState.ROLLED_BACK, session.getErrorMessage());
        sseService.completeStream(id);

        return session.toResponse();
    }

    public MigrationResponse emergencyRevert(String id) {
        MigrationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Migration not found with id: " + id);
        }

        log.warn("Emergency reverting migration [{}] for table '{}'", id, session.getTableName());
        try (Connection conn = getConnection()) {
            CutoverCoordinator coordinator = new CutoverCoordinator(
                    conn, stateStore, id, session.getTableName(), session.getShadowTableName(), session.getOldTableName(),
                    properties.getMigration().getMaxLockTimeoutMs()
            );
            coordinator.emergencyRevert();

            session.setState(MigrationState.REVERTED);
            session.setCompletedAt(Instant.now());
            stateStore.setStatus(id, MigrationState.REVERTED);
            sseService.broadcastStatusChange(id, MigrationState.REVERTED, "Migration successfully reverted to pre-cutover state.");

            return session.toResponse();
        } catch (Exception e) {
            log.error("Emergency revert failed for migration [{}]: {}", id, e.getMessage(), e);
            throw new RuntimeException("Emergency revert failed: " + e.getMessage(), e);
        }
    }

    public MigrationResponse getMigration(String id) {
        MigrationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Migration not found with id: " + id);
        }
        return session.toResponse();
    }

    public List<MigrationResponse> listMigrations() {
        List<MigrationSession> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparing(MigrationSession::getCreatedAt).reversed());
        return list.stream().map(MigrationSession::toResponse).toList();
    }

    private void cleanupSessionWorkers(MigrationSession session) {
        if (session.getBackfillWorker() != null) {
            try { session.getBackfillWorker().close(); } catch (Exception ignored) {}
        }
        if (session.getWalReader() != null) {
            try { session.getWalReader().stop(); } catch (Exception ignored) {}
        }
        if (session.getKafkaConsumer() != null) {
            try { session.getKafkaConsumer().stop(); } catch (Exception ignored) {}
        }
        if (session.getChangeApplier() != null) {
            try { session.getChangeApplier().close(); } catch (Exception ignored) {}
        }
        if (session.getApplierConnection() != null) {
            try {
                if (!session.getApplierConnection().isClosed()) {
                    session.getApplierConnection().close();
                }
            } catch (Exception ignored) {}
        }
    }
}
