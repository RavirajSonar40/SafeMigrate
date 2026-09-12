package com.safemigrate.server.dto;

import java.util.ArrayList;
import java.util.List;

public class DependencyGraphDto {

    private String tableName;
    private List<ForeignKeyRef> incomingForeignKeys = new ArrayList<>();
    private List<ForeignKeyRef> outgoingForeignKeys = new ArrayList<>();
    private List<TriggerRef> triggers = new ArrayList<>();
    private String riskLevel = "LOW"; // LOW, MEDIUM, HIGH
    private List<String> warnings = new ArrayList<>();
    private int totalDependencies = 0;

    public DependencyGraphDto() {
    }

    public DependencyGraphDto(String tableName) {
        this.tableName = tableName;
    }

    public static class ForeignKeyRef {
        private String constraintName;
        private String sourceTable;
        private String sourceColumn;
        private String targetTable;
        private String targetColumn;
        private String onUpdate;
        private String onDelete;

        public ForeignKeyRef() {
        }

        public ForeignKeyRef(String constraintName, String sourceTable, String sourceColumn,
                             String targetTable, String targetColumn, String onUpdate, String onDelete) {
            this.constraintName = constraintName;
            this.sourceTable = sourceTable;
            this.sourceColumn = sourceColumn;
            this.targetTable = targetTable;
            this.targetColumn = targetColumn;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }

        public String getConstraintName() {
            return constraintName;
        }

        public void setConstraintName(String constraintName) {
            this.constraintName = constraintName;
        }

        public String getSourceTable() {
            return sourceTable;
        }

        public void setSourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
        }

        public String getSourceColumn() {
            return sourceColumn;
        }

        public void setSourceColumn(String sourceColumn) {
            this.sourceColumn = sourceColumn;
        }

        public String getTargetTable() {
            return targetTable;
        }

        public void setTargetTable(String targetTable) {
            this.targetTable = targetTable;
        }

        public String getTargetColumn() {
            return targetColumn;
        }

        public void setTargetColumn(String targetColumn) {
            this.targetColumn = targetColumn;
        }

        public String getOnUpdate() {
            return onUpdate;
        }

        public void setOnUpdate(String onUpdate) {
            this.onUpdate = onUpdate;
        }

        public String getOnDelete() {
            return onDelete;
        }

        public void setOnDelete(String onDelete) {
            this.onDelete = onDelete;
        }
    }

    public static class TriggerRef {
        private String triggerName;
        private String event;
        private String timing;
        private String actionStatement;

        public TriggerRef() {
        }

        public TriggerRef(String triggerName, String event, String timing, String actionStatement) {
            this.triggerName = triggerName;
            this.event = event;
            this.timing = timing;
            this.actionStatement = actionStatement;
        }

        public String getTriggerName() {
            return triggerName;
        }

        public void setTriggerName(String triggerName) {
            this.triggerName = triggerName;
        }

        public String getEvent() {
            return event;
        }

        public void setEvent(String event) {
            this.event = event;
        }

        public String getTiming() {
            return timing;
        }

        public void setTiming(String timing) {
            this.timing = timing;
        }

        public String getActionStatement() {
            return actionStatement;
        }

        public void setActionStatement(String actionStatement) {
            this.actionStatement = actionStatement;
        }
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<ForeignKeyRef> getIncomingForeignKeys() {
        return incomingForeignKeys;
    }

    public void setIncomingForeignKeys(List<ForeignKeyRef> incomingForeignKeys) {
        this.incomingForeignKeys = incomingForeignKeys;
    }

    public List<ForeignKeyRef> getOutgoingForeignKeys() {
        return outgoingForeignKeys;
    }

    public void setOutgoingForeignKeys(List<ForeignKeyRef> outgoingForeignKeys) {
        this.outgoingForeignKeys = outgoingForeignKeys;
    }

    public List<TriggerRef> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<TriggerRef> triggers) {
        this.triggers = triggers;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public int getTotalDependencies() {
        return totalDependencies;
    }

    public void setTotalDependencies(int totalDependencies) {
        this.totalDependencies = totalDependencies;
    }
}
