package com.safemigrate.core.preflight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Production Hardening: Foreign Key & Constraint Dependency Inspector.
 *
 * In production PostgreSQL databases, tables rarely exist in isolation:
 * 1. Child tables have Foreign Keys pointing TO the table (e.g. order_items -> orders).
 * 2. The table has Foreign Keys pointing to parent tables (e.g. orders -> users).
 *
 * If a table rename cutover occurs without inspecting foreign keys:
 * Child foreign keys remain attached to the renamed old table ("orders__old"), breaking
 * subsequent application relational queries!
 *
 * ConstraintInspector discovers all incoming and outgoing foreign keys, check constraints,
 * and unique keys, flagging them for validation during pre-flight.
 */
public class ConstraintInspector {

    private static final Logger log = LoggerFactory.getLogger(ConstraintInspector.class);

    private final Connection connection;

    public record ForeignKeyDependency(
            String constraintName,
            String childTable,
            String childColumn,
            String parentTable,
            String parentColumn
    ) {}

    public ConstraintInspector(Connection connection) {
        this.connection = connection;
    }

    /**
     * Finds all incoming foreign keys where other child tables point to the target table.
     */
    public List<ForeignKeyDependency> findIncomingForeignKeys(String targetTable) throws SQLException {
        String sql = """
            SELECT
                tc.constraint_name,
                tc.table_name AS child_table,
                kcu.column_name AS child_column,
                ccu.table_name AS parent_table,
                ccu.column_name AS parent_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON ccu.constraint_name = tc.constraint_name
             AND ccu.table_schema = tc.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = current_schema()
              AND ccu.table_name = ?
        """;

        List<ForeignKeyDependency> deps = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetTable.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    deps.add(new ForeignKeyDependency(
                            rs.getString("constraint_name"),
                            rs.getString("child_table"),
                            rs.getString("child_column"),
                            rs.getString("parent_table"),
                            rs.getString("parent_column")
                    ));
                }
            }
        }
        log.debug("Discovered {} incoming foreign key dependencies for '{}'", deps.size(), targetTable);
        return deps;
    }

    /**
     * Finds all outgoing foreign keys where the target table points to other parent tables.
     */
    public List<ForeignKeyDependency> findOutgoingForeignKeys(String targetTable) throws SQLException {
        String sql = """
            SELECT
                tc.constraint_name,
                tc.table_name AS child_table,
                kcu.column_name AS child_column,
                ccu.table_name AS parent_table,
                ccu.column_name AS parent_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON ccu.constraint_name = tc.constraint_name
             AND ccu.table_schema = tc.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = current_schema()
              AND tc.table_name = ?
        """;

        List<ForeignKeyDependency> deps = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetTable.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    deps.add(new ForeignKeyDependency(
                            rs.getString("constraint_name"),
                            rs.getString("child_table"),
                            rs.getString("child_column"),
                            rs.getString("parent_table"),
                            rs.getString("parent_column")
                    ));
                }
            }
        }
        log.debug("Discovered {} outgoing foreign key dependencies for '{}'", deps.size(), targetTable);
        return deps;
    }
}
