package com.safemigrate.server.dto;

import java.time.Instant;

public class DatabaseConnectionDto {

    private String id;
    private String name;
    private String host;
    private int port = 5432;
    private String databaseName;
    private String username;
    private String password;
    private boolean sslMode = false;
    private boolean isDefault = false;
    private String status = "CONNECTED"; // CONNECTED, ERROR, WARNING
    private String walLevel = "logical";
    private String postgresVersion;
    private long latencyMs = 0;
    private Instant createdAt = Instant.now();

    public DatabaseConnectionDto() {
    }

    public DatabaseConnectionDto(String id, String name, String host, int port, String databaseName, String username, String password, boolean sslMode, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.sslMode = sslMode;
        this.isDefault = isDefault;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSslMode() {
        return sslMode;
    }

    public void setSslMode(boolean sslMode) {
        this.sslMode = sslMode;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWalLevel() {
        return walLevel;
    }

    public void setWalLevel(String walLevel) {
        this.walLevel = walLevel;
    }

    public String getPostgresVersion() {
        return postgresVersion;
    }

    public void setPostgresVersion(String postgresVersion) {
        this.postgresVersion = postgresVersion;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s%s", host, port, databaseName, sslMode ? "?sslmode=require" : "");
    }

    // Mask password in serialized responses
    public DatabaseConnectionDto sanitized() {
        DatabaseConnectionDto clone = new DatabaseConnectionDto();
        clone.id = this.id;
        clone.name = this.name;
        clone.host = this.host;
        clone.port = this.port;
        clone.databaseName = this.databaseName;
        clone.username = this.username;
        clone.password = "••••••••";
        clone.sslMode = this.sslMode;
        clone.isDefault = this.isDefault;
        clone.status = this.status;
        clone.walLevel = this.walLevel;
        clone.postgresVersion = this.postgresVersion;
        clone.latencyMs = this.latencyMs;
        clone.createdAt = this.createdAt;
        return clone;
    }
}
