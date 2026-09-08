package com.safemigrate.core.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Production Hardening: Replication Slot Health & WAL Disk Spillover Guard.
 *
 * An unconsumed logical replication slot in PostgreSQL holds WAL files on disk indefinitely.
 * If SafeMigrate were to pause or crash without monitoring slot retention, pg_wal could expand
 * until the database disk reaches 100% capacity, causing a database-wide crash!
 *
 * ReplicationSlotGuard inspects pg_replication_slots:
 * 1. Active connection status
 * 2. WAL status (normal, reserved, extended, unreserved, lost)
 * 3. Retained WAL bytes on disk
 *
 * Provides early warning or abort triggers before database disk exhaustion occurs.
 */
public class ReplicationSlotGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplicationSlotGuard.class);

    private final Connection connection;

    public record SlotHealth(
            String slotName,
            boolean exists,
            boolean active,
            String walStatus,
            long retainedWalBytes
    ) {
        public boolean isHealthy(long maxAllowedRetainedBytes) {
            return exists && ("normal".equalsIgnoreCase(walStatus) || "reserved".equalsIgnoreCase(walStatus) || walStatus == null)
                    && retainedWalBytes <= maxAllowedRetainedBytes;
        }
    }

    public ReplicationSlotGuard(Connection connection) {
        this.connection = connection;
    }

    /**
     * Inspects the health, status, and disk footprint of a logical replication slot.
     */
    public SlotHealth inspectSlot(String slotName) throws SQLException {
        String sql = "SELECT active, wal_status, " +
                "COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), 0) AS retained_bytes " +
                "FROM pg_replication_slots " +
                "WHERE slot_name = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, slotName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    boolean active = rs.getBoolean("active");
                    String walStatus = rs.getString("wal_status");
                    long retainedBytes = rs.getLong("retained_bytes");

                    log.debug("Replication slot '{}' status: active={}, walStatus={}, retainedBytes={}",
                            slotName, active, walStatus, retainedBytes);

                    return new SlotHealth(slotName, true, active, walStatus, retainedBytes);
                }
            }
        }
        return new SlotHealth(slotName, false, false, "NOT_FOUND", 0L);
    }
}
