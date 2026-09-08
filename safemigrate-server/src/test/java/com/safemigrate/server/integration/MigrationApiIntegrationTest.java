package com.safemigrate.server.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safemigrate.core.state.MigrationState;
import com.safemigrate.loadgen.LoadGenerator;
import com.safemigrate.server.SafeMigrateApplication;
import com.safemigrate.server.config.SafeMigrateProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SafeMigrateApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MigrationApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MigrationApiIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private SafeMigrateProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient httpClient;
    private Connection connection;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = "http://localhost:" + port + "/api/migrations";
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        connection = DriverManager.getConnection(
                properties.getTargetDb().getUrl(),
                properties.getTargetDb().getUsername(),
                properties.getTargetDb().getPassword()
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("POST /api/migrations/preflight - Validate safety checks via API")
    void shouldPerformStandalonePreflightValidation() throws Exception {
        String testTable = "orders_api_preflight_" + System.currentTimeMillis();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, name VARCHAR(64));");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");
        }

        try {
            // 1. Valid DDL should pass
            String validPayload = objectMapper.writeValueAsString(Map.of(
                    "tableName", testTable,
                    "ddlStatement", "ADD COLUMN rating INT DEFAULT 5"
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/preflight"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(validPayload))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);

            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.get("passed").asBoolean()).isTrue();
            assertThat(json.get("tableName").asText()).isEqualTo(testTable);

            // 2. Dangerous DDL should fail
            String dangerousPayload = objectMapper.writeValueAsString(Map.of(
                    "tableName", testTable,
                    "ddlStatement", "DROP DATABASE production"
            ));

            HttpRequest badReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/preflight"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(dangerousPayload))
                    .build();

            HttpResponse<String> badResp = httpClient.send(badReq, HttpResponse.BodyHandlers.ofString());
            assertThat(badResp.statusCode()).isEqualTo(200);

            JsonNode badJson = objectMapper.readTree(badResp.body());
            assertThat(badJson.get("passed").asBoolean()).isFalse();
            assertThat(badJson.get("issues").toString()).contains("PROHIBITED_DDL");

        } finally {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Full Zero-Downtime Migration Lifecycle via REST API & SSE progress streaming")
    void shouldExecuteFullZeroDowntimeMigrationLifecycleViaRestApi() throws Exception {
        long runId = System.currentTimeMillis();
        String testTable = "orders_api_e2e_" + runId;
        String shadowTable = testTable + "__shadow";
        String oldTable = testTable + "__old";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");

            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");

            // Seed 100 historical rows
            String seedSql = "INSERT INTO " + testTable + " (customer_id, amount, status) VALUES (?, ?, ?)";
            try (PreparedStatement seed = connection.prepareStatement(seedSql)) {
                for (int i = 1; i <= 100; i++) {
                    seed.setString(1, "cust_" + i);
                    seed.setBigDecimal(2, BigDecimal.valueOf(i * 10.0));
                    seed.setString(3, "PENDING");
                    seed.addBatch();
                }
                seed.executeBatch();
            }
        }

        try {
            // STEP 1: Submit Migration Request
            String createPayload = objectMapper.writeValueAsString(Map.of(
                    "tableName", testTable,
                    "ddlStatement", "ADD COLUMN priority_level INT DEFAULT 99",
                    "batchSize", 20,
                    "throttleDelayMs", 5,
                    "autoCutover", false
            ));

            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createPayload))
                    .build();

            HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertThat(postResp.statusCode()).isEqualTo(201);

            JsonNode createJson = objectMapper.readTree(postResp.body());
            String migrationId = createJson.get("id").asText();
            assertThat(migrationId).isNotBlank();
            assertThat(createJson.get("tableName").asText()).isEqualTo(testTable);
            assertThat(createJson.get("state").asText()).isIn("INITIALIZING", "BACKFILLING");

            // STEP 2: Connect SSE Stream in Background
            ConcurrentLinkedQueue<String> sseLines = new ConcurrentLinkedQueue<>();
            HttpRequest sseReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/stream"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            CompletableFuture<Void> sseFuture = httpClient.sendAsync(sseReq, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(resp -> resp.body().forEach(line -> {
                        if (!line.isBlank()) {
                            sseLines.add(line);
                        }
                    }));

            // STEP 3: Poll GET /api/migrations/{id} until READY_CUTOVER
            log.info("Polling migration [{}] status...", migrationId);
            JsonNode statusJson = null;
            boolean ready = false;
            for (int i = 0; i < 60; i++) {
                Thread.sleep(500);

                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/" + migrationId))
                        .GET()
                        .build();
                HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
                assertThat(getResp.statusCode()).isEqualTo(200);

                statusJson = objectMapper.readTree(getResp.body());
                String state = statusJson.get("state").asText();
                log.info("Poll #{}: state={}, rowsBackfilled={}", i, state, statusJson.get("rowsBackfilled").asLong());

                if ("READY_CUTOVER".equals(state)) {
                    ready = true;
                    break;
                }
            }

            assertThat(ready).as("Migration should converge to READY_CUTOVER").isTrue();
            assertThat(statusJson.get("rowsBackfilled").asLong()).isEqualTo(100L);
            assertThat(statusJson.get("totalSourceRows").asLong()).isEqualTo(100L);
            assertThat(statusJson.get("progressPercentage").asDouble()).isEqualTo(100.0);

            // STEP 4: Attempt Cutover BEFORE Approval -> 409 Conflict
            HttpRequest unapprovedCutoverReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/cutover"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> unapprovedResp = httpClient.send(unapprovedCutoverReq, HttpResponse.BodyHandlers.ofString());
            assertThat(unapprovedResp.statusCode()).isEqualTo(409);
            assertThat(unapprovedResp.body()).contains("Approval is required before cutover");

            // STEP 5: Approve Migration via POST /api/migrations/{id}/approve
            String approvePayload = objectMapper.writeValueAsString(Map.of(
                    "approver", "lead-dba@enterprise.internal",
                    "notes", "Schema alteration approved for cutover"
            ));
            HttpRequest approveReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/approve"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(approvePayload))
                    .build();
            HttpResponse<String> approveResp = httpClient.send(approveReq, HttpResponse.BodyHandlers.ofString());
            assertThat(approveResp.statusCode()).isEqualTo(200);

            JsonNode approveJson = objectMapper.readTree(approveResp.body());
            assertThat(approveJson.get("approved").asBoolean()).isTrue();
            assertThat(approveJson.get("approvedBy").asText()).isEqualTo("lead-dba@enterprise.internal");

            // STEP 6: Execute Cutover via POST /api/migrations/{id}/cutover
            HttpRequest cutoverReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/cutover"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> cutoverResp = httpClient.send(cutoverReq, HttpResponse.BodyHandlers.ofString());
            assertThat(cutoverResp.statusCode()).isEqualTo(200);

            JsonNode completedJson = objectMapper.readTree(cutoverResp.body());
            assertThat(completedJson.get("state").asText()).isEqualTo("COMPLETED");
            assertThat(completedJson.get("cutoverDurationMs").asLong()).isGreaterThan(0L).isLessThan(2000L);

            // STEP 7: Verify Database Parity & Zero Downtime Promoted Schema
            try (Statement checkStmt = connection.createStatement();
                 ResultSet rs = checkStmt.executeQuery("SELECT id, customer_id, priority_level FROM " + testTable + " ORDER BY id LIMIT 5")) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    assertThat(rs.getInt("priority_level")).isEqualTo(99);
                }
                assertThat(rows).isEqualTo(5);
            }

            // Verify count is exactly 100
            try (Statement checkStmt = connection.createStatement();
                 ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM " + testTable)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(100L);
            }

            // Verify sequence high watermark: insert a new row post-cutover without explicit ID
            try (Statement insertStmt = connection.createStatement()) {
                insertStmt.execute("INSERT INTO " + testTable + " (customer_id, amount, status) VALUES ('post_cutover_cust', 55.00, 'CONFIRMED')");
            }
            try (Statement checkStmt = connection.createStatement();
                 ResultSet rs = checkStmt.executeQuery("SELECT MAX(id) FROM " + testTable)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(101L);
            }

            // Verify SSE events were received
            assertThat(sseLines).isNotEmpty();
            boolean receivedConnected = sseLines.stream().anyMatch(l -> l.contains("connected") || l.contains("CONNECTED"));
            assertThat(receivedConnected).isTrue();

        } finally {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
                stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
                stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("POST /api/migrations/{id}/rollback - Safely abort migration and drop shadow table")
    void shouldRollbackMigrationGracefullyViaRestApi() throws Exception {
        long runId = System.currentTimeMillis();
        String testTable = "orders_api_rollback_" + runId;
        String shadowTable = testTable + "__shadow";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");

            stmt.execute("CREATE TABLE " + testTable + " (id BIGSERIAL PRIMARY KEY, item VARCHAR(64));");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");

            stmt.execute("INSERT INTO " + testTable + " (item) VALUES ('item_1'), ('item_2'), ('item_3');");
        }

        try {
            // Submit migration
            String createPayload = objectMapper.writeValueAsString(Map.of(
                    "tableName", testTable,
                    "ddlStatement", "ADD COLUMN discount NUMERIC(5,2) DEFAULT 0.0"
            ));

            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createPayload))
                    .build();

            HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertThat(postResp.statusCode()).isEqualTo(201);
            String migrationId = objectMapper.readTree(postResp.body()).get("id").asText();

            Thread.sleep(1000);

            // Trigger Rollback
            HttpRequest rollbackReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/rollback?reason=MaintenanceWindowExpired"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> rollbackResp = httpClient.send(rollbackReq, HttpResponse.BodyHandlers.ofString());
            assertThat(rollbackResp.statusCode()).isEqualTo(200);

            JsonNode rollbackJson = objectMapper.readTree(rollbackResp.body());
            assertThat(rollbackJson.get("state").asText()).isEqualTo("ROLLED_BACK");
            assertThat(rollbackJson.get("errorMessage").asText()).contains("MaintenanceWindowExpired");

            // Verify shadow table is dropped
            try (Statement checkStmt = connection.createStatement();
                 ResultSet rs = checkStmt.executeQuery("SELECT to_regclass('" + shadowTable + "')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
            }

            // Verify original table still intact
            try (Statement checkStmt = connection.createStatement();
                 ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM " + testTable)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(3L);
            }

        } finally {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
                stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Flagship: Zero-Downtime Migration under Concurrent Live Workload via REST API")
    void shouldExecuteZeroDowntimeMigrationUnderConcurrentLiveTrafficViaRestApi() throws Exception {
        long runId = System.currentTimeMillis();
        String testTable = "orders_api_live_" + runId;
        String shadowTable = testTable + "__shadow";
        String oldTable = testTable + "__old";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");

            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");

            // Seed 200 initial records
            String seedSql = "INSERT INTO " + testTable + " (customer_id, amount, status) VALUES (?, ?, ?)";
            try (PreparedStatement seed = connection.prepareStatement(seedSql)) {
                for (int i = 1; i <= 200; i++) {
                    seed.setString(1, "seed_cust_" + i);
                    seed.setBigDecimal(2, BigDecimal.valueOf(i * 5.0));
                    seed.setString(3, "PENDING");
                    seed.addBatch();
                }
                seed.executeBatch();
            }
        }

        // 1. Launch Concurrent LoadGenerator (4 virtual threads, 40 ops/sec mixed traffic)
        LoadGenerator loadGen = new LoadGenerator(
                properties.getTargetDb().getUrl(),
                properties.getTargetDb().getUsername(),
                properties.getTargetDb().getPassword(),
                testTable,
                4,
                40
        );

        try {
            loadGen.start();
            log.info("Concurrent LoadGenerator active against table '{}'", testTable);
            Thread.sleep(1000); // Allow initial traffic to establish

            // 2. Submit schema migration request via REST API
            String createPayload = objectMapper.writeValueAsString(Map.of(
                    "tableName", testTable,
                    "ddlStatement", "ADD COLUMN loyalty_tier VARCHAR(32) DEFAULT 'GOLD'",
                    "batchSize", 25,
                    "throttleDelayMs", 5,
                    "autoCutover", false
            ));

            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createPayload))
                    .build();

            HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertThat(postResp.statusCode()).isEqualTo(201);

            JsonNode createJson = objectMapper.readTree(postResp.body());
            String migrationId = createJson.get("id").asText();
            assertThat(migrationId).isNotBlank();

            // 3. Connect SSE Stream in background
            ConcurrentLinkedQueue<String> sseLines = new ConcurrentLinkedQueue<>();
            HttpRequest sseReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/stream"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            CompletableFuture<Void> sseFuture = httpClient.sendAsync(sseReq, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(resp -> resp.body().forEach(line -> {
                        if (!line.isBlank()) {
                            sseLines.add(line);
                        }
                    }));

            // 4. Poll until READY_CUTOVER
            log.info("Polling live migration [{}] under active workload...", migrationId);
            JsonNode statusJson = null;
            boolean ready = false;
            for (int i = 0; i < 60; i++) {
                Thread.sleep(500);

                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/" + migrationId))
                        .GET()
                        .build();
                HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
                assertThat(getResp.statusCode()).isEqualTo(200);

                statusJson = objectMapper.readTree(getResp.body());
                String state = statusJson.get("state").asText();
                log.info("Live Poll #{}: state={}, rowsBackfilled={}, appliedChanges={}",
                        i, state, statusJson.get("rowsBackfilled").asLong(), statusJson.get("totalApplied").asLong());

                if ("READY_CUTOVER".equals(state)) {
                    ready = true;
                    break;
                }
            }

            assertThat(ready).as("Live migration should reach READY_CUTOVER under load").isTrue();
            assertThat(statusJson.get("rowsBackfilled").asLong()).isGreaterThanOrEqualTo(200L);
            log.info("Migration reached READY_CUTOVER. CDC applied changes: Inserts={}, Updates={}, Deletes={}, Total={}",
                    statusJson.get("appliedInserts").asLong(),
                    statusJson.get("appliedUpdates").asLong(),
                    statusJson.get("appliedDeletes").asLong(),
                    statusJson.get("totalApplied").asLong());

            // 5. Approve Migration via POST /api/migrations/{id}/approve
            String approvePayload = objectMapper.writeValueAsString(Map.of(
                    "approver", "lead-dba@enterprise.internal",
                    "notes", "Schema alteration approved under live traffic"
            ));
            HttpRequest approveReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/approve"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(approvePayload))
                    .build();
            HttpResponse<String> approveResp = httpClient.send(approveReq, HttpResponse.BodyHandlers.ofString());
            assertThat(approveResp.statusCode()).isEqualTo(200);

            // 6. Execute Cutover via POST /api/migrations/{id}/cutover
            HttpRequest cutoverReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + migrationId + "/cutover"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> cutoverResp = httpClient.send(cutoverReq, HttpResponse.BodyHandlers.ofString());
            assertThat(cutoverResp.statusCode()).isEqualTo(200);

            JsonNode completedJson = objectMapper.readTree(cutoverResp.body());
            assertThat(completedJson.get("state").asText()).isEqualTo("COMPLETED");
            long cutoverDuration = completedJson.get("cutoverDurationMs").asLong();
            assertThat(cutoverDuration).isGreaterThan(0L).isLessThan(2000L);
            log.info("Cutover completed in {} ms under continuous live traffic!", cutoverDuration);

            // 7. Stop LoadGenerator and assert 0 client-side failures
            loadGen.stop();
            Thread.sleep(500);

            long successOps = loadGen.getSuccessRequests();
            long errorOps = loadGen.getErrorRequests();
            log.info("LoadGenerator Final Metrics: Success={}, Errors={}", successOps, errorOps);

            assertThat(successOps).as("Traffic generator should have executed operations successfully").isGreaterThan(0L);
            assertThat(errorOps).as("Zero downtime guarantee: there must be 0 failed client requests").isEqualTo(0L);

            // 8. Verify Table Parity & Schema Evolution
            try (Statement checkStmt = connection.createStatement()) {
                // Verify new column loyalty_tier exists and is populated for all rows
                ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM " + testTable + " WHERE loyalty_tier = 'GOLD'");
                assertThat(rs.next()).isTrue();
                long goldRows = rs.getLong(1);

                rs = checkStmt.executeQuery("SELECT COUNT(*) FROM " + testTable);
                assertThat(rs.next()).isTrue();
                long totalRows = rs.getLong(1);

                assertThat(goldRows).isEqualTo(totalRows);
                assertThat(totalRows).isGreaterThanOrEqualTo(200L);

                // Verify sequence high-watermark: insert new row post-cutover
                checkStmt.execute("INSERT INTO " + testTable + " (customer_id, amount, status) VALUES ('post_migration_user', 88.88, 'COMPLETED')");
                rs = checkStmt.executeQuery("SELECT loyalty_tier FROM " + testTable + " WHERE customer_id = 'post_migration_user'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("GOLD");
            }

            // Verify SSE events were captured
            assertThat(sseLines).isNotEmpty();
            boolean hasCompleted = sseLines.stream().anyMatch(l -> l.contains("COMPLETED"));
            assertThat(hasCompleted).isTrue();

        } finally {
            loadGen.stop();
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + oldTable + " CASCADE;");
                stmt.execute("DROP TABLE IF EXISTS " + shadowTable + " CASCADE;");
                stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            } catch (Exception ignored) {}
        }
    }
}
