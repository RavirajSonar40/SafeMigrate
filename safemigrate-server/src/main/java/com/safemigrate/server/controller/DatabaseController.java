package com.safemigrate.server.controller;

import com.safemigrate.server.dto.*;
import com.safemigrate.server.service.DatabaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/databases")
@CrossOrigin(origins = "*")
public class DatabaseController {

    private final DatabaseService databaseService;

    public DatabaseController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @GetMapping
    public ResponseEntity<List<DatabaseConnectionDto>> listDatabases() {
        return ResponseEntity.ok(databaseService.listDatabases());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DatabaseConnectionDto> getDatabase(@PathVariable("id") String id) {
        return ResponseEntity.ok(databaseService.getDatabase(id).sanitized());
    }

    @PostMapping
    public ResponseEntity<DatabaseConnectionDto> saveDatabase(@RequestBody DatabaseConnectionDto request) {
        DatabaseConnectionDto saved = databaseService.saveDatabase(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping("/test")
    public ResponseEntity<DatabaseTestResult> testConnection(@RequestBody DatabaseConnectionDto request) {
        DatabaseTestResult result = databaseService.testConnection(request);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDatabase(@PathVariable("id") String id) {
        databaseService.deleteDatabase(id);
        return ResponseEntity.ok(Map.of("message", "Database connection removed: " + id));
    }

    @GetMapping("/{id}/tables")
    public ResponseEntity<List<TableMetadataDto>> listTables(@PathVariable("id") String id) throws SQLException {
        return ResponseEntity.ok(databaseService.listTables(id));
    }

    @GetMapping("/{id}/tables/{tableName}/schema")
    public ResponseEntity<List<ColumnMetadataDto>> getTableSchema(
            @PathVariable("id") String id,
            @PathVariable("tableName") String tableName) throws SQLException {
        return ResponseEntity.ok(databaseService.getTableSchema(id, tableName));
    }

    @GetMapping("/{id}/tables/{tableName}/data")
    public ResponseEntity<TableDataResponse> getTableData(
            @PathVariable("id") String id,
            @PathVariable("tableName") String tableName,
            @RequestParam(name = "limit", defaultValue = "50") int limit) throws SQLException {
        return ResponseEntity.ok(databaseService.getTableData(id, tableName, limit));
    }
}
