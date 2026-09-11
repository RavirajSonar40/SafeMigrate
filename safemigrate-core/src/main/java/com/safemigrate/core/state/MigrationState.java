package com.safemigrate.core.state;

/**
 * Lifecycle states of a zero-downtime schema migration.
 */
public enum MigrationState {
    INITIALIZING,
    BACKFILLING,
    PAUSED,
    RESUMING,
    CATCHING_UP,
    READY_CUTOVER,
    CUTTING_OVER,
    COMPLETED,
    FAILED,
    ROLLED_BACK,
    REVERTED
}
