package com.safemigrate.server.dto;

public class ColumnMetadataDto {

    private String columnName;
    private String dataType;
    private boolean isNullable;
    private String columnDefault;
    private boolean isPrimaryKey;

    public ColumnMetadataDto() {
    }

    public ColumnMetadataDto(String columnName, String dataType, boolean isNullable, String columnDefault, boolean isPrimaryKey) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.isNullable = isNullable;
        this.columnDefault = columnDefault;
        this.isPrimaryKey = isPrimaryKey;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public boolean isNullable() {
        return isNullable;
    }

    public void setNullable(boolean nullable) {
        isNullable = nullable;
    }

    public String getColumnDefault() {
        return columnDefault;
    }

    public void setColumnDefault(String columnDefault) {
        this.columnDefault = columnDefault;
    }

    public boolean isPrimaryKey() {
        return isPrimaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        isPrimaryKey = primaryKey;
    }
}
