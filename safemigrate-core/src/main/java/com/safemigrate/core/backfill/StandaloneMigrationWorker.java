package com.safemigrate.core.backfill;

import com.safemigrate.core.state.StateStore;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;

/**
 * Executable CLI/Worker process that can be launched as an independent OS process
 * (or container/Kubernetes Job) to execute historical backfill.
 *
 * Designed to test true process-level crash recovery (kill -9 / destroyForcibly)
 * where the JVM is killed abruptly without running cleanup or shutdown hooks.
 */
public class StandaloneMigrationWorker {

    private static final Logger log = LoggerFactory.getLogger(StandaloneMigrationWorker.class);

    public static final String CHECKPOINT_PREFIX = "[WORKER-CHECKPOINT]";

    public static void main(String[] args) {
        if (args.length < 10) {
            System.err.println("Usage: StandaloneMigrationWorker <jdbcUrl> <user> <pass> <redisUrl> " +
                    "<migrationId> <sourceTable> <shadowTable> <pkColumn> <columns(comma-sep)> <batchSize> [throttleMs]");
            System.exit(1);
        }

        String jdbcUrl = args[0];
        String user = args[1];
        String pass = args[2];
        String redisUrl = args[3];
        String migrationId = args[4];
        String sourceTable = args[5];
        String shadowTable = args[6];
        String pkColumn = args[7];
        List<String> columns = Arrays.asList(args[8].split(","));
        int batchSize = Integer.parseInt(args[9]);
        long throttleMs = args.length > 10 ? Long.parseLong(args[10]) : 20L;

        System.out.println("[WORKER-START] Launching StandaloneMigrationWorker for " + sourceTable + " -> " + shadowTable);
        System.out.flush();

        RLock lock = null;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, pass);
             StateStore stateStore = new StateStore(redisUrl)) {

            // Acquire distributed lock with a 30s lease
            lock = stateStore.acquireTableLock(sourceTable, 5, 30);
            System.out.println("[WORKER-LOCKED] Acquired distributed lock for " + sourceTable);
            System.out.flush();

            try (BackfillWorker worker = new BackfillWorker(
                    connection, stateStore, migrationId, sourceTable, shadowTable,
                    pkColumn, columns, batchSize, throttleMs
            )) {
                worker.setProgressListener((lastPk, rowsCopied) -> {
                    System.out.println(CHECKPOINT_PREFIX + " lastPk=" + lastPk + " rowsCopied=" + rowsCopied);
                    System.out.flush();
                });

                worker.runBackfill();
            }

            System.out.println("[WORKER-SUCCESS] Backfill completed successfully.");
            System.out.flush();

        } catch (Exception e) {
            System.err.println("[WORKER-ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        } finally {
            // If the process is killed abruptly with SIGKILL, this block will NOT execute.
            // That is precisely what we test in resilience tests.
            if (lock != null && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
