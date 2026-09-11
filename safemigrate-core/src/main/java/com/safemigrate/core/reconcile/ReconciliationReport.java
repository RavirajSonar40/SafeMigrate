package com.safemigrate.core.reconcile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Audit certificate representing the mathematical data reconciliation
 * between the promoted production table and the pre-migration snapshot table.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReconciliationReport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private String tableName;
    private String comparedTable;
    private boolean matched;
    private long sourceRowCount;
    private long targetRowCount;
    private long sourceChecksum;
    private long targetChecksum;
    private List<String> comparedColumns = new ArrayList<>();
    private long discrepancyCount;
    private List<String> sampleDiscrepancies = new ArrayList<>();
    private long executionTimeMs;
    private Instant verifiedAt = Instant.now();

    public ReconciliationReport() {
    }

    public ReconciliationReport(String tableName,
                                String comparedTable,
                                boolean matched,
                                long sourceRowCount,
                                long targetRowCount,
                                long sourceChecksum,
                                long targetChecksum,
                                List<String> comparedColumns,
                                long discrepancyCount,
                                List<String> sampleDiscrepancies,
                                long executionTimeMs) {
        this.tableName = tableName;
        this.comparedTable = comparedTable;
        this.matched = matched;
        this.sourceRowCount = sourceRowCount;
        this.targetRowCount = targetRowCount;
        this.sourceChecksum = sourceChecksum;
        this.targetChecksum = targetChecksum;
        this.comparedColumns = (comparedColumns != null) ? comparedColumns : new ArrayList<>();
        this.discrepancyCount = discrepancyCount;
        this.sampleDiscrepancies = (sampleDiscrepancies != null) ? sampleDiscrepancies : new ArrayList<>();
        this.executionTimeMs = executionTimeMs;
        this.verifiedAt = Instant.now();
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ReconciliationReport to JSON", e);
        }
    }

    public static ReconciliationReport fromJson(String json) {
        try {
            return MAPPER.readValue(json, ReconciliationReport.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ReconciliationReport from JSON", e);
        }
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getComparedTable() {
        return comparedTable;
    }

    public void setComparedTable(String comparedTable) {
        this.comparedTable = comparedTable;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    public long getSourceRowCount() {
        return sourceRowCount;
    }

    public void setSourceRowCount(long sourceRowCount) {
        this.sourceRowCount = sourceRowCount;
    }

    public long getTargetRowCount() {
        return targetRowCount;
    }

    public void setTargetRowCount(long targetRowCount) {
        this.targetRowCount = targetRowCount;
    }

    public long getSourceChecksum() {
        return sourceChecksum;
    }

    public void setSourceChecksum(long sourceChecksum) {
        this.sourceChecksum = sourceChecksum;
    }

    public long getTargetChecksum() {
        return targetChecksum;
    }

    public void setTargetChecksum(long targetChecksum) {
        this.targetChecksum = targetChecksum;
    }

    public List<String> getComparedColumns() {
        return comparedColumns;
    }

    public void setComparedColumns(List<String> comparedColumns) {
        this.comparedColumns = comparedColumns;
    }

    public long getDiscrepancyCount() {
        return discrepancyCount;
    }

    public void setDiscrepancyCount(long discrepancyCount) {
        this.discrepancyCount = discrepancyCount;
    }

    public List<String> getSampleDiscrepancies() {
        return sampleDiscrepancies;
    }

    public void setSampleDiscrepancies(List<String> sampleDiscrepancies) {
        this.sampleDiscrepancies = sampleDiscrepancies;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    @Override
    public String toString() {
        return "ReconciliationReport{" +
                "tableName='" + tableName + '\'' +
                ", comparedTable='" + comparedTable + '\'' +
                ", matched=" + matched +
                ", sourceRowCount=" + sourceRowCount +
                ", targetRowCount=" + targetRowCount +
                ", sourceChecksum=" + sourceChecksum +
                ", targetChecksum=" + targetChecksum +
                ", comparedColumns=" + comparedColumns +
                ", discrepancyCount=" + discrepancyCount +
                ", executionTimeMs=" + executionTimeMs + "ms" +
                '}';
    }
}
