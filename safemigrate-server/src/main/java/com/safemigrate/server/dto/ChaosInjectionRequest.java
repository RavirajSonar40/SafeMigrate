package com.safemigrate.server.dto;

import jakarta.validation.constraints.NotNull;

public class ChaosInjectionRequest {

    @NotNull(message = "action must not be null")
    private ChaosAction action;

    private String notes;

    public ChaosInjectionRequest() {
    }

    public ChaosInjectionRequest(ChaosAction action) {
        this.action = action;
    }

    public ChaosInjectionRequest(ChaosAction action, String notes) {
        this.action = action;
        this.notes = notes;
    }

    public ChaosAction getAction() {
        return action;
    }

    public void setAction(ChaosAction action) {
        this.action = action;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
