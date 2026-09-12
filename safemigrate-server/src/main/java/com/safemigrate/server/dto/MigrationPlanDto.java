package com.safemigrate.server.dto;

import java.util.ArrayList;
import java.util.List;

public class MigrationPlanDto {

    private String tableName;
    private String ddlStatement;
    private String databaseId;
    private long estimatedRows;
    private long tableSizeBytes;
    private String tableSizeBytesFormatted;
    private long estimatedDurationSeconds;
    private String estimatedDurationFormatted;
    private long estimatedWalBytes;
    private String estimatedWalBytesFormatted;
    private long requiredDiskBytes;
    private String requiredDiskBytesFormatted;
    private int expectedCpuLoadPct;
    private long expectedCutoverDurationMs;
    private String riskLevel; // LOW, MEDIUM, HIGH
    private List<String> riskFactors = new ArrayList<>();
    private DependencyGraphDto dependencies;
    private int recommendedBatchSize = 1000;
    private long recommendedThrottleDelayMs = 10;
    private boolean safeToExecute = true;

    public MigrationPlanDto() {
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDdlStatement() {
        return ddlStatement;
    }

    public void setDdlStatement(String ddlStatement) {
        this.ddlStatement = ddlStatement;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }

    public long getEstimatedRows() {
        return estimatedRows;
    }

    public void setEstimatedRows(long estimatedRows) {
        this.estimatedRows = estimatedRows;
    }

    public long getTableSizeBytes() {
        return tableSizeBytes;
    }

    public void setTableSizeBytes(long tableSizeBytes) {
        this.tableSizeBytes = tableSizeBytes;
    }

    public String getTableSizeBytesFormatted() {
        return tableSizeBytesFormatted;
    }

    public void setTableSizeBytesFormatted(String tableSizeBytesFormatted) {
        this.tableSizeBytesFormatted = tableSizeBytesFormatted;
    }

    public long getEstimatedDurationSeconds() {
        return estimatedDurationSeconds;
    }

    public void setEstimatedDurationSeconds(long estimatedDurationSeconds) {
        this.estimatedDurationSeconds = estimatedDurationSeconds;
    }

    public String getEstimatedDurationFormatted() {
        return estimatedDurationFormatted;
    }

    public void setEstimatedDurationFormatted(String estimatedDurationFormatted) {
        this.estimatedDurationFormatted = estimatedDurationFormatted;
    }

    public long getEstimatedWalBytes() {
        return estimatedWalBytes;
    }

    public void setEstimatedWalBytes(long estimatedWalBytes) {
        this.estimatedWalBytes = estimatedWalBytes;
    }

    public String getEstimatedWalBytesFormatted() {
        return estimatedWalBytesFormatted;
    }

    public void setEstimatedWalBytesFormatted(String estimatedWalBytesFormatted) {
        this.estimatedWalBytesFormatted = estimatedWalBytesFormatted;
    }

    public long getRequiredDiskBytes() {
        return requiredDiskBytes;
    }

    public void setRequiredDiskBytes(long requiredDiskBytes) {
        this.requiredDiskBytes = requiredDiskBytes;
    }

    public String getRequiredDiskBytesFormatted() {
        return requiredDiskBytesFormatted;
    }

    public void setRequiredDiskBytesFormatted(String requiredDiskBytesFormatted) {
        this.requiredDiskBytesFormatted = requiredDiskBytesFormatted;
    }

    public int getExpectedCpuLoadPct() {
        return expectedCpuLoadPct;
    }

    public void setExpectedCpuLoadPct(int expectedCpuLoadPct) {
        this.expectedCpuLoadPct = expectedCpuLoadPct;
    }

    public long getExpectedCutoverDurationMs() {
        return expectedCutoverDurationMs;
    }

    public void setExpectedCutoverDurationMs(long expectedCutoverDurationMs) {
        this.expectedCutoverDurationMs = expectedCutoverDurationMs;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public List<String> getRiskFactors() {
        return riskFactors;
    }

    public void setRiskFactors(List<String> riskFactors) {
        this.riskFactors = riskFactors;
    }

    public DependencyGraphDto getDependencies() {
        return dependencies;
    }

    public void setDependencies(DependencyGraphDto dependencies) {
        this.dependencies = dependencies;
    }

    public int getRecommendedBatchSize() {
        return recommendedBatchSize;
    }

    public void setRecommendedBatchSize(int recommendedBatchSize) {
        this.recommendedBatchSize = recommendedBatchSize;
    }

    public long getRecommendedThrottleDelayMs() {
        return recommendedThrottleDelayMs;
    }

    public void setRecommendedThrottleDelayMs(long recommendedThrottleDelayMs) {
        this.recommendedThrottleDelayMs = recommendedThrottleDelayMs;
    }

    public boolean isSafeToExecute() {
        return safeToExecute;
    }

    public void setSafeToExecute(boolean safeToExecute) {
        this.safeToExecute = safeToExecute;
    }
}
