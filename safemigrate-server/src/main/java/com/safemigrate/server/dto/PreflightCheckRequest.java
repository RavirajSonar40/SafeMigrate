package com.safemigrate.server.dto;

import jakarta.validation.constraints.NotBlank;

public class PreflightCheckRequest {

    @NotBlank(message = "tableName must not be blank")
    private String tableName;

    @NotBlank(message = "ddlStatement must not be blank")
    private String ddlStatement;

    public PreflightCheckRequest() {
    }

    public PreflightCheckRequest(String tableName, String ddlStatement) {
        this.tableName = tableName;
        this.ddlStatement = ddlStatement;
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
}
