package com.safemigrate.server.service;

import com.safemigrate.core.applier.ChangeApplier;
import com.safemigrate.core.backfill.BackfillWorker;
import com.safemigrate.core.cutover.CutoverCoordinator;
import com.safemigrate.core.kafka.WalKafkaConsumer;
import com.safemigrate.core.preflight.PreflightReport;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.core.wal.WalReader;
import com.safemigrate.server.dto.MigrationProgressEvent;
import com.safemigrate.server.dto.MigrationResponse;
import org.redisson.api.RLock;

import java.time.Instant;

/**
 * Encapsulates the execution context and live metrics of a single schema migration session.
 */
public class MigrationSession {

    private final String id;
    private final String tableName;
    private final String shadowTableName;
    private final String oldTableName;
    private final String slotName;
    private final String kafkaTopic;
    private final String ddlStatement;
    private final int batchSize;
    private final long throttleDelayMs;
    private final boolean autoCutover;

    private volatile MigrationState state = MigrationState.INITIALIZING;
    private volatile PreflightReport preflightReport;
    private volatile long totalSourceRows = 0L;
    private volatile long rowsBackfilled = 0L;
    private volatile long replicationLagBytes = 0L;
    private volatile Long lastAppliedLsn;
    private volatile long appliedInserts = 0L;
    private volatile long appliedUpdates = 0L;
    private volatile long appliedDeletes = 0L;
    private volatile long totalApplied = 0L;

    private volatile boolean approved = false;
    private volatile String approvedBy;
    private volatile Instant approvedAt;
    private volatile Long cutoverDurationMs;

    private final Instant createdAt = Instant.now();
    private volatile Instant completedAt;
    private volatile String errorMessage;

    // Running worker references for lifecycle control
    private volatile WalReader walReader;
    private volatile WalKafkaConsumer kafkaConsumer;
    private volatile ChangeApplier changeApplier;
    private volatile BackfillWorker backfillWorker;
    private volatile CutoverCoordinator cutoverCoordinator;
    private volatile RLock tableLock;
    private volatile java.sql.Connection applierConnection;

    public MigrationSession(String id, String tableName, String ddlStatement,
                            int batchSize, long throttleDelayMs, boolean autoCutover) {
        this.id = id;
        this.tableName = tableName;
        this.shadowTableName = tableName + "__shadow";
        this.oldTableName = tableName + "__old";
        this.kafkaTopic = "safemigrate.wal." + tableName.toLowerCase();
        String cleanId = id.replace("-", "_").replaceAll("[^a-z0-9_]", "");
        String rawSlot = ("slot_" + tableName.toLowerCase() + "_" + cleanId).replaceAll("[^a-z0-9_]", "_");
        this.slotName = rawSlot.length() > 63 ? rawSlot.substring(0, 63) : rawSlot;
        this.ddlStatement = ddlStatement;
        this.batchSize = batchSize;
        this.throttleDelayMs = throttleDelayMs;
        this.autoCutover = autoCutover;
    }

    public MigrationResponse toResponse() {
        MigrationResponse res = new MigrationResponse();
        res.setId(id);
        res.setTableName(tableName);
        res.setShadowTableName(shadowTableName);
        res.setOldTableName(oldTableName);
        res.setSlotName(slotName);
        res.setKafkaTopic(kafkaTopic);
        res.setDdlStatement(ddlStatement);
        res.setState(state);
        res.setTotalSourceRows(totalSourceRows);
        res.setRowsBackfilled(rowsBackfilled);
        double progress = totalSourceRows > 0
                ? Math.min(100.0, (double) rowsBackfilled / (double) totalSourceRows * 100.0)
                : (state == MigrationState.COMPLETED ? 100.0 : 0.0);
        res.setProgressPercentage(Math.round(progress * 10.0) / 10.0);
        res.setReplicationLagBytes(replicationLagBytes);
        res.setLastAppliedLsn(lastAppliedLsn);
        res.setAppliedInserts(appliedInserts);
        res.setAppliedUpdates(appliedUpdates);
        res.setAppliedDeletes(appliedDeletes);
        res.setTotalApplied(totalApplied);
        res.setApproved(approved);
        res.setApprovedBy(approvedBy);
        res.setApprovedAt(approvedAt != null ? approvedAt.toString() : null);
        res.setCutoverDurationMs(cutoverDurationMs);
        res.setCreatedAt(createdAt.toString());
        res.setCompletedAt(completedAt != null ? completedAt.toString() : null);
        res.setPreflightReport(preflightReport);
        res.setErrorMessage(errorMessage);
        return res;
    }

    public MigrationProgressEvent toProgressEvent() {
        double progress = totalSourceRows > 0
                ? Math.min(100.0, (double) rowsBackfilled / (double) totalSourceRows * 100.0)
                : (state == MigrationState.COMPLETED ? 100.0 : 0.0);
        return new MigrationProgressEvent(
                id,
                tableName,
                state,
                rowsBackfilled,
                totalSourceRows,
                Math.round(progress * 10.0) / 10.0,
                replicationLagBytes,
                appliedInserts,
                appliedUpdates,
                appliedDeletes,
                totalApplied,
                System.currentTimeMillis(),
                errorMessage
        );
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public String getTableName() {
        return tableName;
    }

    public String getShadowTableName() {
        return shadowTableName;
    }

    public String getOldTableName() {
        return oldTableName;
    }

    public String getSlotName() {
        return slotName;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public String getDdlStatement() {
        return ddlStatement;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getThrottleDelayMs() {
        return throttleDelayMs;
    }

    public boolean isAutoCutover() {
        return autoCutover;
    }

    public MigrationState getState() {
        return state;
    }

    public void setState(MigrationState state) {
        this.state = state;
    }

    public PreflightReport getPreflightReport() {
        return preflightReport;
    }

    public void setPreflightReport(PreflightReport preflightReport) {
        this.preflightReport = preflightReport;
    }

    public long getTotalSourceRows() {
        return totalSourceRows;
    }

    public void setTotalSourceRows(long totalSourceRows) {
        this.totalSourceRows = totalSourceRows;
    }

    public long getRowsBackfilled() {
        return rowsBackfilled;
    }

    public void setRowsBackfilled(long rowsBackfilled) {
        this.rowsBackfilled = rowsBackfilled;
    }

    public long getReplicationLagBytes() {
        return replicationLagBytes;
    }

    public void setReplicationLagBytes(long replicationLagBytes) {
        this.replicationLagBytes = replicationLagBytes;
    }

    public Long getLastAppliedLsn() {
        return lastAppliedLsn;
    }

    public void setLastAppliedLsn(Long lastAppliedLsn) {
        this.lastAppliedLsn = lastAppliedLsn;
    }

    public long getAppliedInserts() {
        return appliedInserts;
    }

    public void setAppliedInserts(long appliedInserts) {
        this.appliedInserts = appliedInserts;
    }

    public long getAppliedUpdates() {
        return appliedUpdates;
    }

    public void setAppliedUpdates(long appliedUpdates) {
        this.appliedUpdates = appliedUpdates;
    }

    public long getAppliedDeletes() {
        return appliedDeletes;
    }

    public void setAppliedDeletes(long appliedDeletes) {
        this.appliedDeletes = appliedDeletes;
    }

    public long getTotalApplied() {
        return totalApplied;
    }

    public void setTotalApplied(long totalApplied) {
        this.totalApplied = totalApplied;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Long getCutoverDurationMs() {
        return cutoverDurationMs;
    }

    public void setCutoverDurationMs(Long cutoverDurationMs) {
        this.cutoverDurationMs = cutoverDurationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public WalReader getWalReader() {
        return walReader;
    }

    public void setWalReader(WalReader walReader) {
        this.walReader = walReader;
    }

    public WalKafkaConsumer getKafkaConsumer() {
        return kafkaConsumer;
    }

    public void setKafkaConsumer(WalKafkaConsumer kafkaConsumer) {
        this.kafkaConsumer = kafkaConsumer;
    }

    public ChangeApplier getChangeApplier() {
        return changeApplier;
    }

    public void setChangeApplier(ChangeApplier changeApplier) {
        this.changeApplier = changeApplier;
    }

    public BackfillWorker getBackfillWorker() {
        return backfillWorker;
    }

    public void setBackfillWorker(BackfillWorker backfillWorker) {
        this.backfillWorker = backfillWorker;
    }

    public CutoverCoordinator getCutoverCoordinator() {
        return cutoverCoordinator;
    }

    public void setCutoverCoordinator(CutoverCoordinator cutoverCoordinator) {
        this.cutoverCoordinator = cutoverCoordinator;
    }

    public RLock getTableLock() {
        return tableLock;
    }

    public void setTableLock(RLock tableLock) {
        this.tableLock = tableLock;
    }

    public java.sql.Connection getApplierConnection() {
        return applierConnection;
    }

    public void setApplierConnection(java.sql.Connection applierConnection) {
        this.applierConnection = applierConnection;
    }
}
