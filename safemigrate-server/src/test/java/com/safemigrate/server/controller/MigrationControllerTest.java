package com.safemigrate.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safemigrate.core.preflight.PreflightIssue;
import com.safemigrate.core.preflight.PreflightReport;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.server.dto.ApprovalRequest;
import com.safemigrate.server.dto.CreateMigrationRequest;
import com.safemigrate.server.dto.MigrationResponse;
import com.safemigrate.server.dto.PreflightCheckRequest;
import com.safemigrate.server.service.MigrationService;
import com.safemigrate.server.service.MigrationSseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MigrationController.class)
class MigrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MigrationService migrationService;

    @MockBean
    private MigrationSseService sseService;

    @Test
    @DisplayName("POST /api/migrations - 400 Bad Request when tableName is blank")
    void shouldRejectBlankTableName() throws Exception {
        CreateMigrationRequest req = new CreateMigrationRequest("", "ADD COLUMN score INT");

        mockMvc.perform(post("/api/migrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("POST /api/migrations - 400 Bad Request when ddlStatement is blank")
    void shouldRejectBlankDdlStatement() throws Exception {
        CreateMigrationRequest req = new CreateMigrationRequest("orders", "   ");

        mockMvc.perform(post("/api/migrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("POST /api/migrations - 201 Created when migration submission is accepted")
    void shouldCreateMigrationSuccessfully() throws Exception {
        CreateMigrationRequest req = new CreateMigrationRequest("orders", "ADD COLUMN score INT DEFAULT 0");

        MigrationResponse response = new MigrationResponse();
        response.setId("mig-12345");
        response.setTableName("orders");
        response.setState(MigrationState.INITIALIZING);
        response.setPreflightReport(new PreflightReport("orders", true, Collections.emptyList(), "id", true, 1024, 2048, 100000, false));

        when(migrationService.submitMigration(any(CreateMigrationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/migrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("mig-12345"))
                .andExpect(jsonPath("$.tableName").value("orders"))
                .andExpect(jsonPath("$.state").value("INITIALIZING"));
    }

    @Test
    @DisplayName("POST /api/migrations - 422 Unprocessable Entity when preflight inspection fails")
    void shouldReturn422WhenPreflightFails() throws Exception {
        CreateMigrationRequest req = new CreateMigrationRequest("orders", "DROP DATABASE prod");

        MigrationResponse response = new MigrationResponse();
        response.setId("mig-bad");
        response.setTableName("orders");
        response.setState(MigrationState.FAILED);
        PreflightReport report = new PreflightReport("orders", false,
                List.of(PreflightIssue.error("PROHIBITED_DDL", "Forbidden statement")),
                "id", true, 1024, 2048, 100000, false);
        response.setPreflightReport(report);

        when(migrationService.submitMigration(any(CreateMigrationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/migrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.id").value("mig-bad"))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.preflightReport.passed").value(false));
    }

    @Test
    @DisplayName("GET /api/migrations/{id} - 404 Not Found when ID does not exist")
    void shouldReturn404WhenNotFound() throws Exception {
        when(migrationService.getMigration("mig-missing"))
                .thenThrow(new IllegalArgumentException("Migration not found with id: mig-missing"));

        mockMvc.perform(get("/api/migrations/mig-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Migration not found with id: mig-missing"));
    }

    @Test
    @DisplayName("POST /api/migrations/{id}/approve - 200 OK on valid approval")
    void shouldApproveMigrationSuccessfully() throws Exception {
        ApprovalRequest req = new ApprovalRequest("alice@company.com", "Approved for cutover");

        MigrationResponse response = new MigrationResponse();
        response.setId("mig-123");
        response.setApproved(true);
        response.setApprovedBy("alice@company.com");
        response.setState(MigrationState.READY_CUTOVER);

        when(migrationService.approveMigration(eq("mig-123"), any(ApprovalRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/migrations/mig-123/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.approvedBy").value("alice@company.com"));
    }

    @Test
    @DisplayName("POST /api/migrations/{id}/cutover - 200 OK on cutover execution")
    void shouldExecuteCutoverSuccessfully() throws Exception {
        MigrationResponse response = new MigrationResponse();
        response.setId("mig-123");
        response.setState(MigrationState.COMPLETED);
        response.setCutoverDurationMs(14L);

        when(migrationService.executeCutover("mig-123")).thenReturn(response);

        mockMvc.perform(post("/api/migrations/mig-123/cutover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.cutoverDurationMs").value(14));
    }

    @Test
    @DisplayName("POST /api/migrations/{id}/cutover - 409 Conflict when cutover not ready or not approved")
    void shouldReturn409WhenCutoverNotApproved() throws Exception {
        when(migrationService.executeCutover("mig-123"))
                .thenThrow(new IllegalStateException("Migration [mig-123] requires approval prior to cutover."));

        mockMvc.perform(post("/api/migrations/mig-123/cutover"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Migration [mig-123] requires approval prior to cutover."));
    }

    @Test
    @DisplayName("POST /api/migrations/{id}/rollback - 200 OK")
    void shouldRollbackSuccessfully() throws Exception {
        MigrationResponse response = new MigrationResponse();
        response.setId("mig-123");
        response.setState(MigrationState.ROLLED_BACK);

        when(migrationService.rollbackMigration(eq("mig-123"), any())).thenReturn(response);

        mockMvc.perform(post("/api/migrations/mig-123/rollback")
                        .param("reason", "Detected excessive DB load"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ROLLED_BACK"));
    }

    @Test
    @DisplayName("POST /api/migrations/{id}/revert - 200 OK")
    void shouldEmergencyRevertSuccessfully() throws Exception {
        MigrationResponse response = new MigrationResponse();
        response.setId("mig-123");
        response.setState(MigrationState.REVERTED);

        when(migrationService.emergencyRevert("mig-123")).thenReturn(response);

        mockMvc.perform(post("/api/migrations/mig-123/revert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVERTED"));
    }

    @Test
    @DisplayName("GET /api/migrations/{id}/stream - establishes SSE connection with text/event-stream")
    void shouldEstablishSseStream() throws Exception {
        MigrationResponse response = new MigrationResponse();
        response.setId("mig-123");
        when(migrationService.getMigration("mig-123")).thenReturn(response);

        SseEmitter emitter = new SseEmitter();
        when(sseService.registerEmitter("mig-123")).thenReturn(emitter);

        mockMvc.perform(get("/api/migrations/mig-123/stream"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted());
    }

    @Test
    @DisplayName("POST /api/migrations/preflight - 200 OK with PreflightReport")
    void shouldRunStandalonePreflightCheck() throws Exception {
        PreflightCheckRequest req = new PreflightCheckRequest("orders", "ADD COLUMN note TEXT");
        PreflightReport report = new PreflightReport("orders", true, Collections.emptyList(), "id", true, 1024, 2048, 100000, false);

        when(migrationService.runPreflightCheck("orders", "ADD COLUMN note TEXT")).thenReturn(report);

        mockMvc.perform(post("/api/migrations/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.primaryKeyColumn").value("id"));
    }
}
