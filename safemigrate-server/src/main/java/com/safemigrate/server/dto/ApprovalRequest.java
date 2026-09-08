package com.safemigrate.server.dto;

import jakarta.validation.constraints.NotBlank;

public class ApprovalRequest {

    @NotBlank(message = "approver must not be blank")
    private String approver;

    private String notes;

    public ApprovalRequest() {
    }

    public ApprovalRequest(String approver) {
        this.approver = approver;
    }

    public ApprovalRequest(String approver, String notes) {
        this.approver = approver;
        this.notes = notes;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
