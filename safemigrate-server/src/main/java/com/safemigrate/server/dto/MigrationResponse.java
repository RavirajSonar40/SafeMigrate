package com.safemigrate.server.dto;

import com.safemigrate.core.preflight.PreflightReport;
import com.safemigrate.core.state.MigrationState;

public class MigrationResponse {

    private String id;
    private String tableName;
    private String shadowTableName;
    private String oldTableName;
    private String slotName;
    private String kafkaTopic;
    private String ddlStatement;
    private MigrationState state;
    private long totalSourceRows;
    private long rowsBackfilled;
    private double progressPercentage;
    private long replicationLagBytes;
    private Long lastAppliedLsn;
    private long appliedInserts;
    private long appliedUpdates;
    private long appliedDeletes;
    private long totalApplied;
    private boolean approved;
    private String approvedBy;
    private String approvedAt;
    private Long cutoverDurationMs;
    private String createdAt;
    private String completedAt;
    private PreflightReport preflightReport;
    private String errorMessage;
    private String databaseId;

    public MigrationResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getShadowTableName() {
        return shadowTableName;
    }

    public void setShadowTableName(String shadowTableName) {
        this.shadowTableName = shadowTableName;
    }

    public String getOldTableName() {
        return oldTableName;
    }

    public void setOldTableName(String oldTableName) {
        this.oldTableName = oldTableName;
    }

    public String getSlotName() {
        return slotName;
    }

    public void setSlotName(String slotName) {
        this.slotName = slotName;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public void setKafkaTopic(String kafkaTopic) {
        this.kafkaTopic = kafkaTopic;
    }

    public String getDdlStatement() {
        return ddlStatement;
    }

    public void setDdlStatement(String ddlStatement) {
        this.ddlStatement = ddlStatement;
    }

    public MigrationState getState() {
        return state;
    }

    public void setState(MigrationState state) {
        this.state = state;
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

    public double getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(double progressPercentage) {
        this.progressPercentage = progressPercentage;
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

    public String getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(String approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Long getCutoverDurationMs() {
        return cutoverDurationMs;
    }

    public void setCutoverDurationMs(Long cutoverDurationMs) {
        this.cutoverDurationMs = cutoverDurationMs;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }

    public PreflightReport getPreflightReport() {
        return preflightReport;
    }

    public void setPreflightReport(PreflightReport preflightReport) {
        this.preflightReport = preflightReport;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }
}
