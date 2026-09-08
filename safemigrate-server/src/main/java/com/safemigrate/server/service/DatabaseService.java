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
        initDefaultConnection();
    }

    private void initDefaultConnection() {
        String url = properties.getTargetDb().getUrl();
        String user = properties.getTargetDb().getUsername();
        String pass = properties.getTargetDb().getPassword();

        // Extract host, port, db name from jdbc url
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
        return conn.sanitized();
    }

    public void deleteDatabase(String id) {
        DatabaseConnectionDto conn = connections.get(id);
        if (conn != null && conn.isDefault()) {
            throw new IllegalStateException("Cannot delete the default platform database connection.");
        }
        connections.remove(id);
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

    private void validateTableName(String tableName) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid table identifier: " + tableName);
        }
    }
}
