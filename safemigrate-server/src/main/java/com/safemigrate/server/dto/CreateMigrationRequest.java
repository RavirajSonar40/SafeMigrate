package com.safemigrate.server.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateMigrationRequest {

    @NotBlank(message = "tableName must not be blank")
    private String tableName;

    @NotBlank(message = "ddlStatement must not be blank")
    private String ddlStatement;

    private String databaseId;
    private Integer batchSize;
    private Long throttleDelayMs;
    private Boolean autoCutover;

    public CreateMigrationRequest() {
    }

    public CreateMigrationRequest(String tableName, String ddlStatement) {
        this.tableName = tableName;
        this.ddlStatement = ddlStatement;
    }

    public CreateMigrationRequest(String tableName, String ddlStatement, Integer batchSize, Long throttleDelayMs, Boolean autoCutover) {
        this.tableName = tableName;
        this.ddlStatement = ddlStatement;
        this.batchSize = batchSize;
        this.throttleDelayMs = throttleDelayMs;
        this.autoCutover = autoCutover;
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

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Long getThrottleDelayMs() {
        return throttleDelayMs;
    }

    public void setThrottleDelayMs(Long throttleDelayMs) {
        this.throttleDelayMs = throttleDelayMs;
    }

    public Boolean getAutoCutover() {
        return autoCutover;
    }

    public void setAutoCutover(Boolean autoCutover) {
        this.autoCutover = autoCutover;
    }
}
