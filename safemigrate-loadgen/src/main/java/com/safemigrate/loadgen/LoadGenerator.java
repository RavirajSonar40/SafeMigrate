package com.safemigrate.loadgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-concurrency traffic simulator generating realistic production workload
 * (mixed INSERT, UPDATE, DELETE) against the target PostgreSQL database.
 * Tracks operations with 0-failure validation.
 */
public class LoadGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String DEFAULT_USER = "postgres";
    private static final String DEFAULT_PASSWORD = "password";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final int concurrency;
    private final int targetOpsPerSec;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Metrics counters
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong errorRequests = new AtomicLong(0);
    private final AtomicLong insertCount = new AtomicLong(0);
    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong deleteCount = new AtomicLong(0);

    public LoadGenerator(String jdbcUrl, String username, String password, int concurrency, int targetOpsPerSec) {
        this(jdbcUrl, username, password, "orders", concurrency, targetOpsPerSec);
    }

    public LoadGenerator(String jdbcUrl, String username, String password, String tableName, int concurrency, int targetOpsPerSec) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.concurrency = concurrency;
        this.targetOpsPerSec = targetOpsPerSec;
    }

    public static void main(String[] args) {
        String url = System.getProperty("db.url", DEFAULT_JDBC_URL);
        String user = System.getProperty("db.user", DEFAULT_USER);
        String pass = System.getProperty("db.password", DEFAULT_PASSWORD);
        int concurrency = Integer.parseInt(System.getProperty("concurrency", "8"));
        int rate = Integer.parseInt(System.getProperty("rate", "100")); // 100 ops/sec

        log.info("Starting SafeMigrate Load Generator on {} (concurrency: {}, target rate: {} ops/sec)",
                url, concurrency, rate);

        LoadGenerator generator = new LoadGenerator(url, user, pass, concurrency, rate);
        Runtime.getRuntime().addShutdownHook(new Thread(generator::stop));

        generator.start();
    }

    public void start() {
        running.set(true);

        // Reporter thread (prints stats every second)
        Thread.ofVirtual().name("loadgen-reporter").start(this::reportLoop);

        // Worker threads using Java 21 Virtual Threads
        for (int i = 0; i < concurrency; i++) {
            final int workerId = i;
            Thread.ofVirtual().name("loadgen-worker-" + workerId).start(() -> workerLoop(workerId));
        }

        log.info("SafeMigrate Load Generator active with {} virtual thread workers.", concurrency);
    }

    private void workerLoop(int workerId) {
        Random random = ThreadLocalRandom.current();
        long sleepNsPerOp = (targetOpsPerSec > 0) ? (1_000_000_000L * concurrency / targetOpsPerSec) : 0;

        while (running.get()) {
            long startNs = System.nanoTime();
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                conn.setAutoCommit(true);

                while (running.get()) {
                    int action = random.nextInt(100);
                    totalRequests.incrementAndGet();

                    try {
                        if (action < 60) {
                            // 60% INSERTs
                            executeInsert(conn, random);
                            insertCount.incrementAndGet();
                        } else if (action < 90) {
                            // 30% UPDATEs
                            executeUpdate(conn, random);
                            updateCount.incrementAndGet();
                        } else {
                            // 10% DELETEs
                            executeDelete(conn, random);
                            deleteCount.incrementAndGet();
                        }
                        successRequests.incrementAndGet();
                    } catch (SQLException e) {
                        errorRequests.incrementAndGet();
                        log.error("[Worker {}] Operation failed: {}", workerId, e.getMessage());
                    }

                    if (sleepNsPerOp > 0) {
                        long elapsed = System.nanoTime() - startNs;
                        if (elapsed < sleepNsPerOp) {
                            long sleepMs = (sleepNsPerOp - elapsed) / 1_000_000;
                            if (sleepMs > 0) {
                                Thread.sleep(sleepMs);
                            }
                        }
                        startNs = System.nanoTime();
                    }
                }
            } catch (SQLException e) {
                if (running.get()) {
                    log.error("[Worker {}] Connection dropped, reconnecting in 1s: {}", workerId, e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void executeInsert(Connection conn, Random random) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (customer_id, amount, status) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "cust_" + random.nextInt(1000));
            stmt.setBigDecimal(2, BigDecimal.valueOf(random.nextDouble() * 500 + 5).setScale(2, RoundingMode.HALF_UP));
            stmt.setString(3, getRandomStatus(random));
            stmt.executeUpdate();
        }
    }

    private void executeUpdate(Connection conn, Random random) throws SQLException {
        long targetId = getRandomExistingId(conn, random);
        if (targetId <= 0) return;

        String sql = "UPDATE " + tableName + " SET amount = ?, status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, BigDecimal.valueOf(random.nextDouble() * 700 + 10).setScale(2, RoundingMode.HALF_UP));
            stmt.setString(2, getRandomStatus(random));
            stmt.setLong(3, targetId);
            stmt.executeUpdate();
        }
    }

    private void executeDelete(Connection conn, Random random) throws SQLException {
        long targetId = getRandomExistingId(conn, random);
        if (targetId <= 0) return;

        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, targetId);
            stmt.executeUpdate();
        }
    }

    private long getRandomExistingId(Connection conn, Random random) {
        // Query an ID around a random offset
        String sql = "SELECT id FROM " + tableName + " LIMIT 1 OFFSET ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, random.nextInt(500));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ignored) {
        }
        return -1;
    }

    private String getRandomStatus(Random random) {
        String[] statuses = {"PENDING", "PROCESSING", "COMPLETED", "CANCELLED"};
        return statuses[random.nextInt(statuses.length)];
    }

    private void reportLoop() {
        long lastTotal = 0;
        long lastTime = System.currentTimeMillis();

        while (running.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long now = System.currentTimeMillis();
            long currentTotal = successRequests.get();
            double seconds = (now - lastTime) / 1000.0;
            double opsPerSec = (currentTotal - lastTotal) / (seconds > 0 ? seconds : 1);

            lastTotal = currentTotal;
            lastTime = now;

            System.out.printf(
                    "[SafeMigrate LoadGen] Throughput: %.1f ops/s | Total: %d | Success: %d | Errors: %d | (Inserts: %d, Updates: %d, Deletes: %d)%n",
                    opsPerSec, totalRequests.get(), successRequests.get(), errorRequests.get(),
                    insertCount.get(), updateCount.get(), deleteCount.get()
            );
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Load Generator...");
            System.out.println("Load Generator stopped. Total requests: " + totalRequests.get() + ", Errors: " + errorRequests.get());
        }
    }

    // Getters for testing assertions
    public long getSuccessRequests() { return successRequests.get(); }
    public long getErrorRequests() { return errorRequests.get(); }
}
