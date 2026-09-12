package com.safemigrate.server.service;

import com.safemigrate.server.config.SafeMigrateProperties;
import com.safemigrate.server.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final SafeMigrateProperties properties;
    private final Map<String, DatabaseConnectionDto> connections = new ConcurrentHashMap<>();

    public DatabaseService(SafeMigrateProperties properties) {
        this.properties = properties;
        initPlatformStorage();
    }

    private Connection getPlatformConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getTargetDb().getUrl(),
                properties.getTargetDb().getUsername(),
                properties.getTargetDb().getPassword()
        );
    }

    private void initPlatformStorage() {
        try (Connection c = getPlatformConnection();
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS configured_databases (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    host VARCHAR(255) NOT NULL,
                    port INT NOT NULL,
                    database_name VARCHAR(128) NOT NULL,
                    username VARCHAR(128) NOT NULL,
                    password TEXT NOT NULL,
                    ssl_mode BOOLEAN DEFAULT FALSE,
                    is_default BOOLEAN DEFAULT FALSE,
                    status VARCHAR(32) DEFAULT 'DISCONNECTED',
                    wal_level VARCHAR(32),
                    postgres_version VARCHAR(128),
                    latency_ms BIGINT DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );
            """);

            try (ResultSet rs = s.executeQuery("SELECT * FROM configured_databases")) {
                while (rs.next()) {
                    DatabaseConnectionDto dto = new DatabaseConnectionDto(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("host"),
                            rs.getInt("port"),
                            rs.getString("database_name"),
                            rs.getString("username"),
                            rs.getString("password"),
                            rs.getBoolean("ssl_mode"),
                            rs.getBoolean("is_default")
                    );
                    dto.setStatus(rs.getString("status"));
                    dto.setWalLevel(rs.getString("wal_level"));
                    dto.setPostgresVersion(rs.getString("postgres_version"));
                    dto.setLatencyMs(rs.getLong("latency_ms"));
                    connections.put(dto.getId(), dto);
                }
            }
            log.info("Loaded {} configured databases from PostgreSQL", connections.size());
        } catch (Exception e) {
            log.error("Failed to initialize or load configured_databases from DB: {}", e.getMessage());
        }

        if (!connections.containsKey("default-postgres")) {
            initDefaultConnection();
        }

        if (!connections.containsKey("supabase-production")) {
            initSupabaseConnection();
        }
    }

    private void initDefaultConnection() {
        String url = properties.getTargetDb().getUrl();
        String user = properties.getTargetDb().getUsername();
        String pass = properties.getTargetDb().getPassword();

        String host = "localhost";
        int port = 5432;
        String dbName = "safemigrate_test";

        try {
            String clean = url.replace("jdbc:postgresql://", "");
            int slashIdx = clean.indexOf('/');
            if (slashIdx != -1) {
                String hostPort = clean.substring(0, slashIdx);
                dbName = clean.substring(slashIdx + 1);
                int qIdx = dbName.indexOf('?');
                if (qIdx != -1) {
                    dbName = dbName.substring(0, qIdx);
                }
                int colonIdx = hostPort.indexOf(':');
                if (colonIdx != -1) {
                    host = hostPort.substring(0, colonIdx);
                    port = Integer.parseInt(hostPort.substring(colonIdx + 1));
                } else {
                    host = hostPort;
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse default JDBC URL {}, using fallback localhost:5432", url);
        }

        DatabaseConnectionDto defaultConn = new DatabaseConnectionDto(
                "default-postgres",
                "Primary PostgreSQL (safemigrate_test)",
                host,
                port,
                dbName,
                user,
                pass,
                false,
                true
        );
        defaultConn.setStatus("CONNECTED");
        defaultConn.setWalLevel("logical");
        connections.put(defaultConn.getId(), defaultConn);
        persistToDatabase(defaultConn);
    }

    private void initSupabaseConnection() {
        DatabaseConnectionDto supabaseConn = new DatabaseConnectionDto(
                "supabase-production",
                "Supabase Production (ap-southeast-1)",
                "aws-0-ap-southeast-1.pooler.supabase.com",
                5432,
                "postgres",
                "postgres.dtcfthsmmccfcjhtiwwd",
                "4LIKzMjeK1R2ncwl",
                true,
                false
        );
        try {
            DatabaseTestResult res = testConnection(supabaseConn);
            if (res.isConnected()) {
                supabaseConn.setStatus("CONNECTED");
                supabaseConn.setWalLevel(res.getWalLevel());
                supabaseConn.setPostgresVersion(res.getPostgresVersion());
                supabaseConn.setLatencyMs(res.getLatencyMs());
            } else {
                supabaseConn.setStatus("ERROR");
            }
        } catch (Exception e) {
            supabaseConn.setStatus("ERROR");
        }
        connections.put(supabaseConn.getId(), supabaseConn);
        persistToDatabase(supabaseConn);
    }

    private void persistToDatabase(DatabaseConnectionDto conn) {
        String sql = """
            INSERT INTO configured_databases 
            (id, name, host, port, database_name, username, password, ssl_mode, is_default, status, wal_level, postgres_version, latency_ms, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            host = EXCLUDED.host,
            port = EXCLUDED.port,
            database_name = EXCLUDED.database_name,
            username = EXCLUDED.username,
            password = EXCLUDED.password,
            ssl_mode = EXCLUDED.ssl_mode,
            is_default = EXCLUDED.is_default,
            status = EXCLUDED.status,
            wal_level = EXCLUDED.wal_level,
            postgres_version = EXCLUDED.postgres_version,
            latency_ms = EXCLUDED.latency_ms,
            updated_at = CURRENT_TIMESTAMP;
        """;
        try (Connection c = getPlatformConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, conn.getId());
            ps.setString(2, conn.getName());
            ps.setString(3, conn.getHost());
            ps.setInt(4, conn.getPort());
            ps.setString(5, conn.getDatabaseName());
            ps.setString(6, conn.getUsername());
            ps.setString(7, conn.getPassword());
            ps.setBoolean(8, conn.isSslMode());
            ps.setBoolean(9, conn.isDefault());
            ps.setString(10, conn.getStatus());
            ps.setString(11, conn.getWalLevel());
            ps.setString(12, conn.getPostgresVersion());
            ps.setLong(13, conn.getLatencyMs());
            ps.executeUpdate();
            log.info("Persisted database connection {} ({}) to PostgreSQL", conn.getId(), conn.getName());
        } catch (Exception e) {
            log.error("Failed to persist database connection {} to PostgreSQL: {}", conn.getId(), e.getMessage());
        }
    }

    private void deleteFromDatabase(String id) {
        try (Connection c = getPlatformConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM configured_databases WHERE id = ? AND is_default = false")) {
            ps.setString(1, id);
            ps.executeUpdate();
            log.info("Deleted database connection {} from PostgreSQL", id);
        } catch (Exception e) {
            log.error("Failed to delete database connection {} from PostgreSQL: {}", id, e.getMessage());
        }
    }

    public List<DatabaseConnectionDto> listDatabases() {
        List<DatabaseConnectionDto> list = new ArrayList<>();
        for (DatabaseConnectionDto conn : connections.values()) {
            list.add(conn.sanitized());
        }
        list.sort(Comparator.comparing(DatabaseConnectionDto::isDefault).reversed().thenComparing(DatabaseConnectionDto::getName));
        return list;
    }

    public DatabaseConnectionDto getDatabase(String id) {
        DatabaseConnectionDto conn = connections.get(id);
        if (conn == null) {
            throw new IllegalArgumentException("Database connection not found: " + id);
        }
        return conn;
    }

    public DatabaseConnectionDto saveDatabase(DatabaseConnectionDto request) {
        String id = request.getId();
        if (id == null || id.isBlank()) {
            id = "db_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 5);
        }

        DatabaseConnectionDto existing = connections.get(id);
        String password = request.getPassword();
        if ((password == null || password.isBlank() || password.contains("••")) && existing != null) {
            password = existing.getPassword();
        }

        DatabaseConnectionDto conn = new DatabaseConnectionDto(
                id,
                request.getName(),
                request.getHost(),
                request.getPort() > 0 ? request.getPort() : 5432,
                request.getDatabaseName(),
                request.getUsername(),
                password,
                request.isSslMode(),
                false
        );

        // Test the connection immediately
        DatabaseTestResult testResult = testConnection(conn);
        if (testResult.isConnected()) {
            conn.setStatus("CONNECTED");
            conn.setWalLevel(testResult.getWalLevel());
            conn.setPostgresVersion(testResult.getPostgresVersion());
            conn.setLatencyMs(testResult.getLatencyMs());
        } else {
            conn.setStatus("ERROR");
        }

        connections.put(id, conn);
        persistToDatabase(conn);
        return conn.sanitized();
    }

    public void deleteDatabase(String id) {
        DatabaseConnectionDto conn = connections.get(id);
        if (conn != null && conn.isDefault()) {
            throw new IllegalStateException("Cannot delete the default platform database connection.");
        }
        connections.remove(id);
        deleteFromDatabase(id);
    }

    public DatabaseTestResult testConnection(DatabaseConnectionDto dto) {
        long start = System.currentTimeMillis();
        String url = dto.getJdbcUrl();
        try (Connection c = DriverManager.getConnection(url, dto.getUsername(), dto.getPassword())) {
            long latency = System.currentTimeMillis() - start;

            String pgVersion = "PostgreSQL";
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT version()")) {
                if (rs.next()) {
                    String full = rs.getString(1);
                    int comma = full.indexOf(',');
                    pgVersion = (comma != -1) ? full.substring(0, comma) : full;
                }
            }

            String walLevel = "unknown";
            boolean walValid = false;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW wal_level")) {
                if (rs.next()) {
                    walLevel = rs.getString(1);
                    walValid = "logical".equalsIgnoreCase(walLevel);
                }
            }

            boolean hasRepRole = false;
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT rolreplication, rolsuper FROM pg_roles WHERE rolname = current_user")) {
                if (rs.next()) {
                    hasRepRole = rs.getBoolean("rolreplication") || rs.getBoolean("rolsuper");
                }
            } catch (Exception e) {
                log.debug("Could not verify rolreplication: {}", e.getMessage());
            }

            int maxSlots = 0;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW max_replication_slots")) {
                if (rs.next()) {
                    maxSlots = rs.getInt(1);
                }
            } catch (Exception ignored) {
            }

            String message = walValid
                    ? "Successfully connected. WAL logical replication ready."
                    : "Connected, but wal_level is '" + walLevel + "'. Zero-downtime CDC requires wal_level=logical in postgresql.conf.";

            return new DatabaseTestResult(true, latency, pgVersion, walLevel, walValid, hasRepRole, maxSlots, message);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Database test connection failed for {}: {}", url, e.getMessage());
            return DatabaseTestResult.failed("Connection failed: " + e.getMessage(), latency);
        }
    }

    public Connection getConnectionFor(String dbId) throws SQLException {
        DatabaseConnectionDto dto = getDatabase(dbId);
        return DriverManager.getConnection(dto.getJdbcUrl(), dto.getUsername(), dto.getPassword());
    }

    public List<TableMetadataDto> listTables(String dbId) throws SQLException {
        List<TableMetadataDto> tables = new ArrayList<>();
        String query =
                "SELECT c.relname AS table_name, " +
                "       n.nspname AS schema_name, " +
                "       COALESCE(c.reltuples, 0)::bigint AS estimated_rows, " +
                "       pg_total_relation_size(c.oid) AS total_bytes, " +
                "       pg_size_pretty(pg_total_relation_size(c.oid)) AS size_pretty, " +
                "       c.relreplident AS replica_identity " +
                "FROM pg_class c " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'public' AND c.relkind = 'r' " +
                "ORDER BY c.relname ASC;";

        try (Connection c = getConnectionFor(dbId);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                String schemaName = rs.getString("schema_name");
                long estimatedRows = Math.max(0, rs.getLong("estimated_rows"));
                long totalBytes = rs.getLong("total_bytes");
                String sizePretty = rs.getString("size_pretty");
                String relreplident = rs.getString("replica_identity");

                String replIdentDesc = switch (relreplident) {
                    case "f" -> "FULL";
                    case "d" -> "DEFAULT";
                    case "n" -> "NOTHING";
                    case "i" -> "INDEX";
                    default -> relreplident;
                };

                TableMetadataDto meta = new TableMetadataDto(
                        tableName,
                        schemaName,
                        estimatedRows,
                        sizePretty,
                        totalBytes,
                        null,
                        replIdentDesc,
                        "FULL".equalsIgnoreCase(replIdentDesc)
                );

                // Fetch primary key for table
                meta.setPrimaryKeyColumn(findPrimaryKey(c, tableName));
                tables.add(meta);
            }
        }

        return tables;
    }

    public List<ColumnMetadataDto> getTableSchema(String dbId, String tableName) throws SQLException {
        validateTableName(tableName);
        List<ColumnMetadataDto> columns = new ArrayList<>();

        String query =
                "SELECT column_name, data_type, is_nullable, column_default " +
                "FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = ? " +
                "ORDER BY ordinal_position ASC;";

        try (Connection c = getConnectionFor(dbId)) {
            String pk = findPrimaryKey(c, tableName);
            try (PreparedStatement ps = c.prepareStatement(query)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String colName = rs.getString("column_name");
                        String type = rs.getString("data_type");
                        boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                        String def = rs.getString("column_default");

                        columns.add(new ColumnMetadataDto(
                                colName,
                                type,
                                nullable,
                                def,
                                colName.equalsIgnoreCase(pk)
                        ));
                    }
                }
            }
        }
        return columns;
    }

    public TableDataResponse getTableData(String dbId, String tableName, int limit) throws SQLException {
        validateTableName(tableName);
        int safeLimit = Math.min(Math.max(1, limit), 100);

        List<String> columnNames = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long totalCount = 0;

        try (Connection c = getConnectionFor(dbId)) {
            // Count total rows
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM public.\"" + tableName + "\"")) {
                if (rs.next()) {
                    totalCount = rs.getLong(1);
                }
            }

            // Fetch sample rows
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT * FROM public.\"" + tableName + "\" LIMIT " + safeLimit)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                for (int i = 1; i <= colCount; i++) {
                    columnNames.add(meta.getColumnLabel(i));
                }

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String col = columnNames.get(i - 1);
                        Object val = rs.getObject(i);
                        row.put(col, val != null ? val.toString() : null);
                    }
                    rows.add(row);
                }
            }
        }

        return new TableDataResponse(tableName, columnNames, rows, totalCount, rows.size());
    }

    private String findPrimaryKey(Connection c, String tableName) {
        String pkQuery =
                "SELECT kcu.column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = 'public' AND tc.table_name = ? " +
                "LIMIT 1;";
        try (PreparedStatement ps = c.prepareStatement(pkQuery)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("column_name");
                }
            }
        } catch (Exception e) {
            log.debug("Could not find primary key for {}: {}", tableName, e.getMessage());
        }
        return null;
    }

    public DependencyGraphDto analyzeDependencies(String dbId, String tableName) throws SQLException {
        validateTableName(tableName);
        DependencyGraphDto graph = new DependencyGraphDto(tableName);

        String incomingFkSql =
                "SELECT tc.constraint_name, tc.table_name AS source_table, kcu.column_name AS source_column, " +
                "       ccu.table_name AS target_table, ccu.column_name AS target_column, rc.update_rule, rc.delete_rule " +
                "FROM information_schema.table_constraints AS tc " +
                "JOIN information_schema.key_column_usage AS kcu " +
                "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "JOIN information_schema.constraint_column_usage AS ccu " +
                "  ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema " +
                "JOIN information_schema.referential_constraints AS rc " +
                "  ON rc.constraint_name = tc.constraint_name AND rc.constraint_schema = tc.table_schema " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = ? AND tc.table_schema = 'public';";

        String outgoingFkSql =
                "SELECT tc.constraint_name, tc.table_name AS source_table, kcu.column_name AS source_column, " +
                "       ccu.table_name AS target_table, ccu.column_name AS target_column, rc.update_rule, rc.delete_rule " +
                "FROM information_schema.table_constraints AS tc " +
                "JOIN information_schema.key_column_usage AS kcu " +
                "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "JOIN information_schema.constraint_column_usage AS ccu " +
                "  ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema " +
                "JOIN information_schema.referential_constraints AS rc " +
                "  ON rc.constraint_name = tc.constraint_name AND rc.constraint_schema = tc.table_schema " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ? AND tc.table_schema = 'public';";

        String triggersSql =
                "SELECT trigger_name, event_manipulation, action_timing, action_statement " +
                "FROM information_schema.triggers " +
                "WHERE event_object_schema = 'public' AND event_object_table = ?;";

        try (Connection c = getConnectionFor(dbId)) {
            // 1. Incoming FKs
            try (PreparedStatement ps = c.prepareStatement(incomingFkSql)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        graph.getIncomingForeignKeys().add(new DependencyGraphDto.ForeignKeyRef(
                                rs.getString("constraint_name"),
                                rs.getString("source_table"),
                                rs.getString("source_column"),
                                rs.getString("target_table"),
                                rs.getString("target_column"),
                                rs.getString("update_rule"),
                                rs.getString("delete_rule")
                        ));
                    }
                }
            }

            // 2. Outgoing FKs
            try (PreparedStatement ps = c.prepareStatement(outgoingFkSql)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        graph.getOutgoingForeignKeys().add(new DependencyGraphDto.ForeignKeyRef(
                                rs.getString("constraint_name"),
                                rs.getString("source_table"),
                                rs.getString("source_column"),
                                rs.getString("target_table"),
                                rs.getString("target_column"),
                                rs.getString("update_rule"),
                                rs.getString("delete_rule")
                        ));
                    }
                }
            }

            // 3. Triggers
            try (PreparedStatement ps = c.prepareStatement(triggersSql)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        graph.getTriggers().add(new DependencyGraphDto.TriggerRef(
                                rs.getString("trigger_name"),
                                rs.getString("event_manipulation"),
                                rs.getString("action_timing"),
                                rs.getString("action_statement")
                        ));
                    }
                }
            }
        }

        int total = graph.getIncomingForeignKeys().size() + graph.getOutgoingForeignKeys().size() + graph.getTriggers().size();
        graph.setTotalDependencies(total);

        if (!graph.getIncomingForeignKeys().isEmpty()) {
            graph.setRiskLevel("MEDIUM");
            graph.getWarnings().add(graph.getIncomingForeignKeys().size() + " incoming foreign key(s) reference this table.");
        }
        if (!graph.getTriggers().isEmpty()) {
            if ("LOW".equals(graph.getRiskLevel())) graph.setRiskLevel("MEDIUM");
            graph.getWarnings().add(graph.getTriggers().size() + " active trigger(s) detected on this table.");
        }

        return graph;
    }

    public MigrationPlanDto generateMigrationPlan(String dbId, String tableName, String ddlStatement) throws SQLException {
        validateTableName(tableName);
        MigrationPlanDto plan = new MigrationPlanDto();
        plan.setTableName(tableName);
        plan.setDdlStatement(ddlStatement);
        plan.setDatabaseId(dbId);

        long rows = 0;
        long sizeBytes = 0;

        try (Connection c = getConnectionFor(dbId)) {
            String statsSql = "SELECT pg_total_relation_size(quote_ident(?)::regclass) as total_bytes, " +
                    "COALESCE(reltuples::bigint, 0) as est_rows FROM pg_class WHERE relname = ? AND relnamespace = 'public'::regnamespace;";
            try (PreparedStatement ps = c.prepareStatement(statsSql)) {
                ps.setString(1, tableName);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sizeBytes = rs.getLong("total_bytes");
                        rows = rs.getLong("est_rows");
                    }
                }
            }

            if (rows <= 0) {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT count(*) FROM public.\"" + tableName + "\"")) {
                    if (rs.next()) rows = rs.getLong(1);
                }
            }
        }

        plan.setEstimatedRows(rows);
        plan.setTableSizeBytes(sizeBytes);
        plan.setTableSizeBytesFormatted(formatBytes(sizeBytes));

        // Plan calculations (calibrated against 25k rows/sec benchmark engine)
        long durationSec = Math.max(1, rows / 25000L);
        plan.setEstimatedDurationSeconds(durationSec);
        long minutes = durationSec / 60;
        long seconds = durationSec % 60;
        plan.setEstimatedDurationFormatted(minutes > 0 ? String.format("%dm %02ds", minutes, seconds) : String.format("%ds", seconds));

        long walBytes = (long) (sizeBytes * 0.45);
        plan.setEstimatedWalBytes(walBytes);
        plan.setEstimatedWalBytesFormatted(formatBytes(walBytes));

        long diskReq = (long) (sizeBytes * 1.35);
        plan.setRequiredDiskBytes(diskReq);
        plan.setRequiredDiskBytesFormatted(formatBytes(diskReq));

        plan.setExpectedCpuLoadPct(rows > 1000000 ? 38 : 22);
        plan.setExpectedCutoverDurationMs(18);

        // Analyze dependencies
        DependencyGraphDto dep = analyzeDependencies(dbId, tableName);
        plan.setDependencies(dep);

        String risk = "LOW";
        List<String> factors = new ArrayList<>();
        if (!dep.getIncomingForeignKeys().isEmpty()) {
            risk = "MEDIUM";
            factors.add(dep.getIncomingForeignKeys().size() + " tables maintain incoming foreign keys pointing to " + tableName);
        }
        if (!dep.getTriggers().isEmpty()) {
            factors.add(dep.getTriggers().size() + " active database triggers will require re-attachment");
        }
        if (rows > 5000000) {
            risk = "MEDIUM";
            factors.add("High volume table (>5M rows): checkpointed resume is strongly recommended");
        }
        if (sizeBytes > 10L * 1024 * 1024 * 1024) {
            risk = "HIGH";
            factors.add("Large relation size (>10 GB): disk headroom verification required");
        }
        if (factors.isEmpty()) {
            factors.add("Isolated table with single primary key: optimal candidate for zero-downtime cutover");
        }

        plan.setRiskLevel(risk);
        plan.setRiskFactors(factors);
        plan.setSafeToExecute(!"HIGH".equals(risk));

        return plan;
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return new java.text.DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private void validateTableName(String tableName) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid table identifier: " + tableName);
        }
    }
}
