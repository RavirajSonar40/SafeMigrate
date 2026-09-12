package com.safemigrate.server.dto;

import java.time.Instant;

public class RecoveryResponse {

    private String migrationId;
    private boolean recovered;
    private String message;
    private String restoredComponent;
    private Long resumedFromPk;
    private long eventsLost = 0L;
    private long duplicateEvents = 0L;
    private String currentState;
    private Instant recoveredAt = Instant.now();

    public RecoveryResponse() {
    }

    public RecoveryResponse(String migrationId, boolean recovered, String message, String restoredComponent, Long resumedFromPk) {
        this.migrationId = migrationId;
        this.recovered = recovered;
        this.message = message;
        this.restoredComponent = restoredComponent;
        this.resumedFromPk = resumedFromPk;
    }

    public String getMigrationId() {
        return migrationId;
    }

    public void setMigrationId(String migrationId) {
        this.migrationId = migrationId;
    }

    public boolean isRecovered() {
        return recovered;
    }

    public void setRecovered(boolean recovered) {
        this.recovered = recovered;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRestoredComponent() {
        return restoredComponent;
    }

    public void setRestoredComponent(String restoredComponent) {
        this.restoredComponent = restoredComponent;
    }

    public Long getResumedFromPk() {
        return resumedFromPk;
    }

    public void setResumedFromPk(Long resumedFromPk) {
        this.resumedFromPk = resumedFromPk;
    }

    public long getEventsLost() {
        return eventsLost;
    }

    public void setEventsLost(long eventsLost) {
        this.eventsLost = eventsLost;
    }

    public long getDuplicateEvents() {
        return duplicateEvents;
    }

    public void setDuplicateEvents(long duplicateEvents) {
        this.duplicateEvents = duplicateEvents;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public void setRecoveredAt(Instant recoveredAt) {
        this.recoveredAt = recoveredAt;
    }
}
