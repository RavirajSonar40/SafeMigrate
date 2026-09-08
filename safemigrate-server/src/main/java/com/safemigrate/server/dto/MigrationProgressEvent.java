package com.safemigrate.server.dto;

import com.safemigrate.core.state.MigrationState;

public class MigrationProgressEvent {

    private String migrationId;
    private String tableName;
    private MigrationState state;
    private long rowsBackfilled;
    private long totalSourceRows;
    private double progressPercentage;
    private long replicationLagBytes;
    private long appliedInserts;
    private long appliedUpdates;
    private long appliedDeletes;
    private long totalApplied;
    private long timestamp;
    private String errorMessage;

    public MigrationProgressEvent() {
    }

    public MigrationProgressEvent(String migrationId, String tableName, MigrationState state,
                                long rowsBackfilled, long totalSourceRows, double progressPercentage,
                                long replicationLagBytes, long appliedInserts, long appliedUpdates,
                                long appliedDeletes, long totalApplied, long timestamp, String errorMessage) {
        this.migrationId = migrationId;
        this.tableName = tableName;
        this.state = state;
        this.rowsBackfilled = rowsBackfilled;
        this.totalSourceRows = totalSourceRows;
        this.progressPercentage = progressPercentage;
        this.replicationLagBytes = replicationLagBytes;
        this.appliedInserts = appliedInserts;
        this.appliedUpdates = appliedUpdates;
        this.appliedDeletes = appliedDeletes;
        this.totalApplied = totalApplied;
        this.timestamp = timestamp;
        this.errorMessage = errorMessage;
    }

    public String getMigrationId() {
        return migrationId;
    }

    public void setMigrationId(String migrationId) {
        this.migrationId = migrationId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public MigrationState getState() {
        return state;
    }

    public void setState(MigrationState state) {
        this.state = state;
    }

    public long getRowsBackfilled() {
        return rowsBackfilled;
    }

    public void setRowsBackfilled(long rowsBackfilled) {
        this.rowsBackfilled = rowsBackfilled;
    }

    public long getTotalSourceRows() {
        return totalSourceRows;
    }

    public void setTotalSourceRows(long totalSourceRows) {
        this.totalSourceRows = totalSourceRows;
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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
