package com.safemigrate.core.applier;

import com.safemigrate.core.kafka.WalKafkaConsumer;
import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import com.safemigrate.core.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replays PostgreSQL WAL change events onto the shadow table.
 * Implements idempotent replay semantics, dynamic type introspection with explicit casts,
 * and Redis LSN checkpointing.
 */
public class ChangeApplier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChangeApplier.class);

    private final Connection connection;
    private final StateStore stateStore;
    private final String migrationId;
    private final String sourceTable;
    private final String shadowTable;
    private String pkColumn;

    // Introspected column metadata: lowercase column name -> PostgreSQL type for CAST(? AS <type>)
    private final Map<String, String> columnCastTypes = new HashMap<>();
    // Original casing of column names
    private final Map<String, String> columnOriginalNames = new HashMap<>();

    // Metrics counters
    private final AtomicLong totalApplied = new AtomicLong(0);
    private final AtomicLong insertCount = new AtomicLong(0);
    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong deleteCount = new AtomicLong(0);
    private final AtomicLong lastAppliedLsn = new AtomicLong(0);

    public ChangeApplier(Connection connection,
                         StateStore stateStore,
                         String migrationId,
                         String sourceTable,
                         String shadowTable,
                         String pkColumn) throws SQLException {
        this.connection = connection;
        this.stateStore = stateStore;
        this.migrationId = migrationId;
        this.sourceTable = sourceTable;
        this.shadowTable = (shadowTable != null && !shadowTable.isBlank()) ? shadowTable : sourceTable + "__shadow";
        this.pkColumn = pkColumn;

        introspectShadowTable();
    }

    public ChangeApplier(Connection connection,
                         String sourceTable,
                         String shadowTable) throws SQLException {
        this(connection, null, null, sourceTable, shadowTable, null);
    }

    /**
     * Introspects the target shadow table to discover column names, data types, and primary key.
     */
    private void introspectShadowTable() throws SQLException {
        String sql = """
            SELECT column_name, udt_name, data_type
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position;
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, shadowTable.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("column_name");
                    String udtName = rs.getString("udt_name");
                    String castType = resolveCastType(udtName);

                    columnCastTypes.put(colName.toLowerCase(), castType);
                    columnOriginalNames.put(colName.toLowerCase(), colName);
                }
            }
        }

        if (columnCastTypes.isEmpty()) {
            throw new IllegalStateException("Shadow table '" + shadowTable + "' does not exist or has no columns.");
        }

        if (pkColumn == null || pkColumn.isBlank()) {
            pkColumn = findPrimaryKey(shadowTable);
        }
        pkColumn = pkColumn.toLowerCase();

        log.info("Introspected shadow table '{}': columns={}, pk='{}'",
                shadowTable, columnCastTypes.keySet(), pkColumn);
    }

    private String resolveCastType(String udtName) {
        if (udtName == null) return "text";
        if (udtName.startsWith("_")) {
            return udtName.substring(1) + "[]";
        }
        return udtName;
    }

    private String findPrimaryKey(String tableName) throws SQLException {
        String sql = """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
              AND tc.table_schema = 'public'
              AND tc.table_name = ?
            LIMIT 1;
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        throw new IllegalStateException("Table '" + tableName + "' has no primary key.");
    }

    /**
     * Applies a single WAL change event onto the shadow table.
     */
    public synchronized void apply(WalChangeEvent event) throws SQLException {
        if (event == null) return;

        // Skip events not meant for this table
        if (!sourceTable.equalsIgnoreCase(event.table())) {
            log.trace("Skipping event for table '{}', expected '{}'", event.table(), sourceTable);
            return;
        }

        OperationType op = event.operation();
        switch (op) {
            case INSERT -> applyInsert(event);
            case UPDATE -> applyUpdate(event);
            case DELETE -> applyDelete(event);
            default -> log.debug("Skipping unhandled operation type: {}", op);
        }

        long lsn = event.lsn();
        if (lsn > 0) {
            lastAppliedLsn.set(lsn);
            if (stateStore != null && migrationId != null) {
                stateStore.checkpointAppliedLsn(migrationId, lsn);
            }
        }
        totalApplied.incrementAndGet();
    }

    /**
     * Replays INSERT using idempotent UPSERT:
     * INSERT INTO shadow (cols) VALUES (CAST(? AS udt)...)
     * ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col...
     */
    private void applyInsert(WalChangeEvent event) throws SQLException {
        Map<String, Object> newValues = event.newValues();
        if (newValues.isEmpty()) {
            log.warn("INSERT event has empty newValues: {}", event);
            return;
        }

        List<String> insertCols = new ArrayList<>();
        List<String> insertTypes = new ArrayList<>();
        List<Object> insertVals = new ArrayList<>();

        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String colLower = entry.getKey().toLowerCase();
            if (columnCastTypes.containsKey(colLower)) {
                insertCols.add(columnOriginalNames.get(colLower));
                insertTypes.add(columnCastTypes.get(colLower));
                insertVals.add(entry.getValue());
            }
        }

        if (insertCols.isEmpty()) {
            log.warn("No matching shadow table columns found for INSERT event: {}", newValues);
            return;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO \"").append(shadowTable).append("\" (");
        for (int i = 0; i < insertCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("\"").append(insertCols.get(i)).append("\"");
        }
        sql.append(") VALUES (");
        for (int i = 0; i < insertCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("CAST(? AS ").append(insertTypes.get(i)).append(")");
        }
        sql.append(") ON CONFLICT (\"").append(columnOriginalNames.get(pkColumn)).append("\") ");

        // Build DO UPDATE SET for non-PK columns
        List<String> updateCols = new ArrayList<>();
        for (String col : insertCols) {
            if (!col.equalsIgnoreCase(pkColumn)) {
                updateCols.add(col);
            }
        }

        if (updateCols.isEmpty()) {
            sql.append("DO NOTHING");
        } else {
            sql.append("DO UPDATE SET ");
            for (int i = 0; i < updateCols.size(); i++) {
                if (i > 0) sql.append(", ");
                String col = updateCols.get(i);
                sql.append("\"").append(col).append("\" = EXCLUDED.\"").append(col).append("\"");
            }
        }

        executePrepared(sql.toString(), insertVals);
        insertCount.incrementAndGet();
        log.trace("Applied INSERT to shadow table '{}': {}", shadowTable, newValues);
    }

    /**
     * Replays UPDATE:
     * First attempts UPDATE shadow SET col = CAST(? AS udt)... WHERE pk = CAST(? AS pk_udt).
     * If 0 rows are updated (e.g. row not yet backfilled into shadow) and full tuple is present,
     * falls back to idempotent UPSERT so the live write is captured before backfill arrives.
     */
    private void applyUpdate(WalChangeEvent event) throws SQLException {
        Map<String, Object> newValues = event.newValues();
        if (newValues.isEmpty()) {
            log.warn("UPDATE event has empty newValues: {}", event);
            return;
        }

        Object pkVal = event.getPrimaryKeyValue(pkColumn);
        if (pkVal == null) {
            pkVal = event.oldValues().get(pkColumn);
        }
        if (pkVal == null) {
            log.warn("Cannot find PK '{}' in UPDATE event: {}", pkColumn, event);
            return;
        }

        List<String> setCols = new ArrayList<>();
        List<String> setTypes = new ArrayList<>();
        List<Object> setVals = new ArrayList<>();

        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String colLower = entry.getKey().toLowerCase();
            if (!colLower.equals(pkColumn) && columnCastTypes.containsKey(colLower)) {
                setCols.add(columnOriginalNames.get(colLower));
                setTypes.add(columnCastTypes.get(colLower));
                setVals.add(entry.getValue());
            }
        }

        if (setCols.isEmpty()) {
            log.trace("UPDATE event has no non-PK columns to update.");
            return;
        }

        // 1. Attempt standard UPDATE
        StringBuilder updateSql = new StringBuilder();
        updateSql.append("UPDATE \"").append(shadowTable).append("\" SET ");
        for (int i = 0; i < setCols.size(); i++) {
            if (i > 0) updateSql.append(", ");
            updateSql.append("\"").append(setCols.get(i)).append("\" = CAST(? AS ").append(setTypes.get(i)).append(")");
        }
        updateSql.append(" WHERE \"").append(columnOriginalNames.get(pkColumn)).append("\" = CAST(? AS ")
                .append(columnCastTypes.get(pkColumn)).append(")");

        List<Object> allUpdateParams = new ArrayList<>(setVals);
        allUpdateParams.add(pkVal);

        int rowsAffected = executePrepared(updateSql.toString(), allUpdateParams);

        if (rowsAffected == 0) {
            // Row not yet in shadow table! Upsert to ensure live write is preserved.
            log.debug("UPDATE affected 0 rows in '{}' for PK={}. Replaying as UPSERT.", shadowTable, pkVal);
            applyInsert(event);
        } else {
            updateCount.incrementAndGet();
            log.trace("Applied UPDATE to shadow table '{}': PK={}, rowsAffected={}", shadowTable, pkVal, rowsAffected);
        }
    }

    /**
     * Replays DELETE:
     * DELETE FROM shadow WHERE pk = CAST(? AS pk_udt).
     */
    private void applyDelete(WalChangeEvent event) throws SQLException {
        Object pkVal = event.getPrimaryKeyValue(pkColumn);
        if (pkVal == null) {
            pkVal = event.oldValues().get(pkColumn);
        }
        if (pkVal == null) {
            log.warn("Cannot find PK '{}' in DELETE event: {}", pkColumn, event);
            return;
        }

        String sql = String.format("DELETE FROM \"%s\" WHERE \"%s\" = CAST(? AS %s)",
                shadowTable, columnOriginalNames.get(pkColumn), columnCastTypes.get(pkColumn));

        int rowsDeleted = executePrepared(sql, Collections.singletonList(pkVal));
        deleteCount.incrementAndGet();
        log.trace("Applied DELETE to shadow table '{}': PK={}, rowsDeleted={}", shadowTable, pkVal, rowsDeleted);
    }

    private int executePrepared(String sql, List<Object> params) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object val = params.get(i);
                if (val == null) {
                    stmt.setNull(i + 1, Types.OTHER);
                } else {
                    stmt.setString(i + 1, val.toString());
                }
            }
            return stmt.executeUpdate();
        }
    }

    /**
     * Starts continuous replay loop consuming events from WalKafkaConsumer.
     */
    public void start(WalKafkaConsumer consumer) {
        log.info("Starting ChangeApplier listening on WalKafkaConsumer for table '{}' -> '{}'",
                sourceTable, shadowTable);
        consumer.start(event -> {
            try {
                apply(event);
            } catch (SQLException e) {
                log.error("Failed to apply WAL event {}: {}", event, e.getMessage(), e);
                throw new RuntimeException("ChangeApplier replay failure", e);
            }
        });
    }

    // --- Metrics & Accessors ---

    public long getTotalApplied() {
        return totalApplied.get();
    }

    public long getInsertCount() {
        return insertCount.get();
    }

    public long getUpdateCount() {
        return updateCount.get();
    }

    public long getDeleteCount() {
        return deleteCount.get();
    }

    public long getLastAppliedLsn() {
        return lastAppliedLsn.get();
    }

    public String getPkColumn() {
        return pkColumn;
    }

    public String getShadowTable() {
        return shadowTable;
    }

    public Map<String, String> getColumnCastTypes() {
        return Collections.unmodifiableMap(columnCastTypes);
    }

    @Override
    public void close() {
        log.info("Closed ChangeApplier for '{}' (Total applied: {}, Inserts: {}, Updates: {}, Deletes: {})",
                shadowTable, totalApplied.get(), insertCount.get(), updateCount.get(), deleteCount.get());
    }
}
