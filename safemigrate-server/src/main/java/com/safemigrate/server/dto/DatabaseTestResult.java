package com.safemigrate.server.dto;

public class DatabaseTestResult {

    private boolean connected;
    private long latencyMs;
    private String postgresVersion;
    private String walLevel;
    private boolean walLevelValid; // true if 'logical'
    private boolean hasReplicationRole;
    private int maxReplicationSlots;
    private String message;

    public DatabaseTestResult() {
    }

    public DatabaseTestResult(boolean connected, long latencyMs, String postgresVersion, String walLevel, boolean walLevelValid, boolean hasReplicationRole, int maxReplicationSlots, String message) {
        this.connected = connected;
        this.latencyMs = latencyMs;
        this.postgresVersion = postgresVersion;
        this.walLevel = walLevel;
        this.walLevelValid = walLevelValid;
        this.hasReplicationRole = hasReplicationRole;
        this.maxReplicationSlots = maxReplicationSlots;
        this.message = message;
    }

    public static DatabaseTestResult failed(String message, long latencyMs) {
        DatabaseTestResult res = new DatabaseTestResult();
        res.setConnected(false);
        res.setLatencyMs(latencyMs);
        res.setMessage(message);
        return res;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getPostgresVersion() {
        return postgresVersion;
    }

    public void setPostgresVersion(String postgresVersion) {
        this.postgresVersion = postgresVersion;
    }

    public String getWalLevel() {
        return walLevel;
    }

    public void setWalLevel(String walLevel) {
        this.walLevel = walLevel;
    }

    public boolean isWalLevelValid() {
        return walLevelValid;
    }

    public void setWalLevelValid(boolean walLevelValid) {
        this.walLevelValid = walLevelValid;
    }

    public boolean isHasReplicationRole() {
        return hasReplicationRole;
    }

    public void setHasReplicationRole(boolean hasReplicationRole) {
        this.hasReplicationRole = hasReplicationRole;
    }

    public int getMaxReplicationSlots() {
        return maxReplicationSlots;
    }

    public void setMaxReplicationSlots(int maxReplicationSlots) {
        this.maxReplicationSlots = maxReplicationSlots;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
