package com.safemigrate.server.dto;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class WorkerStatusDto {

    private String id;
    private String name;
    private String component; // WAL_READER, BACKFILL_WORKER, CHANGE_APPLIER, CUTOVER_COORDINATOR
    private String status;    // HEALTHY, RUNNING, PAUSED, INTERRUPTED, IDLE, COMPLETED, ERROR
    private String lsn;
    private Long offset;
    private Long currentPk;
    private double throughputEventsPerSec;
    private long lagBytes;
    private long lagMs;
    private String message;
    private Instant lastHeartbeat = Instant.now();
    private Map<String, Object> metrics = new HashMap<>();

    public WorkerStatusDto() {
    }

    public WorkerStatusDto(String id, String name, String component, String status) {
        this.id = id;
        this.name = name;
        this.component = component;
        this.status = status;
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

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLsn() {
        return lsn;
    }

    public void setLsn(String lsn) {
        this.lsn = lsn;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public Long getCurrentPk() {
        return currentPk;
    }

    public void setCurrentPk(Long currentPk) {
        this.currentPk = currentPk;
    }

    public double getThroughputEventsPerSec() {
        return throughputEventsPerSec;
    }

    public void setThroughputEventsPerSec(double throughputEventsPerSec) {
        this.throughputEventsPerSec = throughputEventsPerSec;
    }

    public long getLagBytes() {
        return lagBytes;
    }

    public void setLagBytes(long lagBytes) {
        this.lagBytes = lagBytes;
    }

    public long getLagMs() {
        return lagMs;
    }

    public void setLagMs(long lagMs) {
        this.lagMs = lagMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }
}
