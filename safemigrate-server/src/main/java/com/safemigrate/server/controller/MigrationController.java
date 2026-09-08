package com.safemigrate.server.controller;

import com.safemigrate.core.preflight.PreflightReport;
import com.safemigrate.server.dto.ApprovalRequest;
import com.safemigrate.server.dto.CreateMigrationRequest;
import com.safemigrate.server.dto.MigrationResponse;
import com.safemigrate.server.dto.PreflightCheckRequest;
import com.safemigrate.server.service.MigrationService;
import com.safemigrate.server.service.MigrationSseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.SQLException;
import java.util.List;

@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private final MigrationService migrationService;
    private final MigrationSseService sseService;

    public MigrationController(MigrationService migrationService, MigrationSseService sseService) {
        this.migrationService = migrationService;
        this.sseService = sseService;
    }

    @PostMapping
    public ResponseEntity<MigrationResponse> createMigration(@RequestBody @Valid CreateMigrationRequest request) {
        MigrationResponse response = migrationService.submitMigration(request);
        HttpStatus status = response.getPreflightReport() != null && !response.getPreflightReport().passed()
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public ResponseEntity<List<MigrationResponse>> listMigrations() {
        return ResponseEntity.ok(migrationService.listMigrations());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MigrationResponse> getMigration(@PathVariable("id") String id) {
        return ResponseEntity.ok(migrationService.getMigration(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<MigrationResponse> approveMigration(
            @PathVariable("id") String id,
            @RequestBody @Valid ApprovalRequest request) {
        return ResponseEntity.ok(migrationService.approveMigration(id, request));
    }

    @PostMapping("/{id}/cutover")
    public ResponseEntity<MigrationResponse> executeCutover(@PathVariable("id") String id) {
        return ResponseEntity.ok(migrationService.executeCutover(id));
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<MigrationResponse> rollbackMigration(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false, defaultValue = "User requested rollback") String reason) {
        return ResponseEntity.ok(migrationService.rollbackMigration(id, reason));
    }

    @PostMapping("/{id}/revert")
    public ResponseEntity<MigrationResponse> emergencyRevert(@PathVariable("id") String id) {
        return ResponseEntity.ok(migrationService.emergencyRevert(id));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMigrationEvents(@PathVariable("id") String id) {
        // Ensure migration exists
        migrationService.getMigration(id);
        return sseService.registerEmitter(id);
    }

    @PostMapping("/preflight")
    public ResponseEntity<PreflightReport> runPreflightCheck(@RequestBody @Valid PreflightCheckRequest request) throws SQLException {
        PreflightReport report = migrationService.runPreflightCheck(request.getTableName(), request.getDdlStatement(), request.getDatabaseId());
        return ResponseEntity.ok(report);
    }
}
