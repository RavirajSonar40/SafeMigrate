package com.safemigrate.core.state;

import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Manages distributed state, progress checkpoints, and concurrency locks in Redis.
 */
public class StateStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private final RedissonClient redisson;

    public StateStore(String redisAddress) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisAddress)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8)
                .setTimeout(3000);
        this.redisson = Redisson.create(config);
        log.info("Connected StateStore to Redis at: {}", redisAddress);
    }

    /**
     * Acquires an exclusive distributed lock for the given table so only one migration can run at a time.
     */
    public RLock acquireTableLock(String tableName, long waitTimeSeconds, long leaseTimeSeconds) throws InterruptedException {
        return acquireTableLock(tableName, null, waitTimeSeconds, leaseTimeSeconds);
    }

    /**
     * Acquires an exclusive distributed lock scoped to a specific database + table combination.
     */
    public RLock acquireTableLock(String tableName, String databaseId, long waitTimeSeconds, long leaseTimeSeconds) throws InterruptedException {
        String dbScope = (databaseId != null && !databaseId.isBlank()) ? databaseId : "default";
        String lockKey = "safemigrate:lock:table:" + dbScope + ":" + tableName.toLowerCase();
        RLock lock = redisson.getLock(lockKey);
        boolean acquired = lock.tryLock(waitTimeSeconds, leaseTimeSeconds, TimeUnit.SECONDS);
        if (!acquired) {
            throw new IllegalStateException("Failed to acquire migration lock for table '" + tableName +
                    "' on database '" + dbScope + "'. Another migration is currently active.");
        }
        log.info("Acquired exclusive migration lock for table: {} on db: {}", tableName, dbScope);
        return lock;
    }

    /**
     * Releases an acquired distributed lock.
     */
    public void releaseTableLock(RLock lock) {
        if (lock != null) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                } else if (lock.isLocked()) {
                    lock.forceUnlock();
                }
                log.info("Released table migration lock: {}", lock.getName());
            } catch (Exception e) {
                log.warn("Failed to release table migration lock {}: {}", lock.getName(), e.getMessage());
            }
        }
    }

    /**
     * Checks whether an exclusive distributed lock is currently held on the given table.
     */
    public boolean isTableLocked(String tableName) {
        return isTableLocked(tableName, null);
    }

    public boolean isTableLocked(String tableName, String databaseId) {
        String dbScope = (databaseId != null && !databaseId.isBlank()) ? databaseId : "default";
        String lockKey = "safemigrate:lock:table:" + dbScope + ":" + tableName.toLowerCase();
        RLock lock = redisson.getLock(lockKey);
        return lock.isLocked();
    }

    /**
     * Forcefully unlocks the table lock (used during recovery or failover).
     */
    public void forceReleaseTableLock(String tableName) {
        forceReleaseTableLock(tableName, null);
    }

    public void forceReleaseTableLock(String tableName, String databaseId) {
        String dbScope = (databaseId != null && !databaseId.isBlank()) ? databaseId : "default";
        String lockKey = "safemigrate:lock:table:" + dbScope + ":" + tableName.toLowerCase();
        RLock lock = redisson.getLock(lockKey);
        if (lock.isLocked()) {
            lock.forceUnlock();
            log.info("Forcefully released table lock: {}", lockKey);
        }
    }

    // --- State & Status Management ---

    public void setStatus(String migrationId, MigrationState state) {
        RBucket<String> bucket = redisson.getBucket("migration:" + migrationId + ":status");
        bucket.set(state.name());
        log.info("Migration [{}] transitioned to state: {}", migrationId, state);
    }

    public MigrationState getStatus(String migrationId) {
        RBucket<String> bucket = redisson.getBucket("migration:" + migrationId + ":status");
        String val = bucket.get();
        return val != null ? MigrationState.valueOf(val) : MigrationState.INITIALIZING;
    }

    // --- Backfill Checkpointing ---

    public void checkpointBackfill(String migrationId, long lastCopiedPk, long rowsBackfilled) {
        RBucket<Long> pkBucket = redisson.getBucket("migration:" + migrationId + ":last_pk");
        RBucket<Long> rowsBucket = redisson.getBucket("migration:" + migrationId + ":rows_backfilled");

        pkBucket.set(lastCopiedPk);
        rowsBucket.set(rowsBackfilled);
        log.trace("Migration [{}] checkpoint: lastPk={}, backfilledRows={}", migrationId, lastCopiedPk, rowsBackfilled);
    }

    public Long getLastCopiedPk(String migrationId) {
        RBucket<Long> bucket = redisson.getBucket("migration:" + migrationId + ":last_pk");
        return bucket.get();
    }

    public long getRowsBackfilled(String migrationId) {
        RBucket<Long> bucket = redisson.getBucket("migration:" + migrationId + ":rows_backfilled");
        Long val = bucket.get();
        return val != null ? val : 0L;
    }

    // --- Replication LSN Checkpointing ---

    public void checkpointAppliedLsn(String migrationId, long lsn) {
        RBucket<Long> bucket = redisson.getBucket("migration:" + migrationId + ":last_applied_lsn");
        Long current = bucket.get();
        if (current == null || lsn > current) {
            bucket.set(lsn);
        }
    }

    public Long getLastAppliedLsn(String migrationId) {
        RBucket<Long> bucket = redisson.getBucket("migration:" + migrationId + ":last_applied_lsn");
        return bucket.get();
    }

    @Override
    public void close() {
        if (redisson != null && !redisson.isShutdown()) {
            redisson.shutdown();
        }
    }
}
