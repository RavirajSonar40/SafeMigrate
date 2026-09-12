package com.safemigrate.server.dto;

import java.time.Instant;

public class ChaosInjectionResponse {

    private String migrationId;
    private ChaosAction action;
    private boolean success;
    private String message;
    private String affectedComponent;
    private String previousState;
    private String currentState;
    private Long checkpointPk;
    private Instant injectedAt = Instant.now();

    public ChaosInjectionResponse() {
    }

    public ChaosInjectionResponse(String migrationId, ChaosAction action, boolean success, String message) {
        this.migrationId = migrationId;
        this.action = action;
        this.success = success;
        this.message = message;
    }

    public String getMigrationId() {
        return migrationId;
    }

    public void setMigrationId(String migrationId) {
        this.migrationId = migrationId;
    }

    public ChaosAction getAction() {
        return action;
    }

    public void setAction(ChaosAction action) {
        this.action = action;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAffectedComponent() {
        return affectedComponent;
    }

    public void setAffectedComponent(String affectedComponent) {
        this.affectedComponent = affectedComponent;
    }

    public String getPreviousState() {
        return previousState;
    }

    public void setPreviousState(String previousState) {
        this.previousState = previousState;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public Long getCheckpointPk() {
        return checkpointPk;
    }

    public void setCheckpointPk(Long checkpointPk) {
        this.checkpointPk = checkpointPk;
    }

    public Instant getInjectedAt() {
        return injectedAt;
    }

    public void setInjectedAt(Instant injectedAt) {
        this.injectedAt = injectedAt;
    }
}
