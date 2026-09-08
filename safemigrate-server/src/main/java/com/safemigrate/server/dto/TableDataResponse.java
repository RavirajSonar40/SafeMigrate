package com.safemigrate.server.dto;

import java.util.List;
import java.util.Map;

public class TableDataResponse {

    private String tableName;
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private long totalRows;
    private int returnedRows;

    public TableDataResponse() {
    }

    public TableDataResponse(String tableName, List<String> columns, List<Map<String, Object>> rows, long totalRows, int returnedRows) {
        this.tableName = tableName;
        this.columns = columns;
        this.rows = rows;
        this.totalRows = totalRows;
        this.returnedRows = returnedRows;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public int getReturnedRows() {
        return returnedRows;
    }

    public void setReturnedRows(int returnedRows) {
        this.returnedRows = returnedRows;
    }
}
