package com.safemigrate.server.dto;

import java.util.List;

public class TableMetadataDto {

    private String tableName;
    private String schemaName = "public";
    private long estimatedRowCount;
    private String sizeBytesFormatted;
    private long sizeBytes;
    private String primaryKeyColumn;
    private String replicaIdentity; // 'FULL', 'DEFAULT', 'NOTHING', 'INDEX'
    private boolean replicaIdentityFull;
    private List<ColumnMetadataDto> columns;

    public TableMetadataDto() {
    }

    public TableMetadataDto(String tableName, String schemaName, long estimatedRowCount, String sizeBytesFormatted, long sizeBytes, String primaryKeyColumn, String replicaIdentity, boolean replicaIdentityFull) {
        this.tableName = tableName;
        this.schemaName = schemaName;
        this.estimatedRowCount = estimatedRowCount;
        this.sizeBytesFormatted = sizeBytesFormatted;
        this.sizeBytes = sizeBytes;
        this.primaryKeyColumn = primaryKeyColumn;
        this.replicaIdentity = replicaIdentity;
        this.replicaIdentityFull = replicaIdentityFull;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public long getEstimatedRowCount() {
        return estimatedRowCount;
    }

    public void setEstimatedRowCount(long estimatedRowCount) {
        this.estimatedRowCount = estimatedRowCount;
    }

    public String getSizeBytesFormatted() {
        return sizeBytesFormatted;
    }

    public void setSizeBytesFormatted(String sizeBytesFormatted) {
        this.sizeBytesFormatted = sizeBytesFormatted;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getPrimaryKeyColumn() {
        return primaryKeyColumn;
    }

    public void setPrimaryKeyColumn(String primaryKeyColumn) {
        this.primaryKeyColumn = primaryKeyColumn;
    }

    public String getReplicaIdentity() {
        return replicaIdentity;
    }

    public void setReplicaIdentity(String replicaIdentity) {
        this.replicaIdentity = replicaIdentity;
    }

    public boolean isReplicaIdentityFull() {
        return replicaIdentityFull;
    }

    public void setReplicaIdentityFull(boolean replicaIdentityFull) {
        this.replicaIdentityFull = replicaIdentityFull;
    }

    public List<ColumnMetadataDto> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMetadataDto> columns) {
        this.columns = columns;
    }
}
