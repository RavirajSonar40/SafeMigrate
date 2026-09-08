package com.safemigrate.core.wal;

import com.safemigrate.core.model.WalChangeEvent;
import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tails PostgreSQL Write-Ahead Log (WAL) via PGReplicationStream and dispatches decoded change events.
 */
public class WalReader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalReader.class);

    private final PostgresReplicationConnectionFactory connectionFactory;
    private final String slotName;
    private final String targetTable;
    private final WalMessageDecoder decoder;
    private final Consumer<WalChangeEvent> eventConsumer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Connection replicationConnection;
    private PGReplicationStream stream;
    private Thread readerThread;

    public WalReader(PostgresReplicationConnectionFactory connectionFactory,
                     String slotName,
                     String targetTable,
                     WalMessageDecoder decoder,
                     Consumer<WalChangeEvent> eventConsumer) {
        this.connectionFactory = connectionFactory;
        this.slotName = slotName;
        this.targetTable = targetTable;
        this.decoder = decoder;
        this.eventConsumer = eventConsumer;
    }

    /**
     * Starts the WAL reader streaming in a dedicated background virtual thread.
     */
    public synchronized void start(Long startLsn) throws SQLException {
        if (running.get()) {
            log.warn("WalReader is already running.");
            return;
        }

        // Ensure replication slot exists with test_decoding plugin
        connectionFactory.ensureReplicationSlotExists(slotName, "test_decoding");

        replicationConnection = connectionFactory.createReplicationConnection();
        PGConnection pgConnection = replicationConnection.unwrap(PGConnection.class);

        LogSequenceNumber startPosition = (startLsn != null && startLsn > 0)
                ? LogSequenceNumber.valueOf(startLsn)
                : LogSequenceNumber.INVALID_LSN;

        log.info("Starting replication stream on slot '{}' from LSN: {}", slotName, startPosition);

        stream = pgConnection.getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName(slotName)
                .withStartPosition(startPosition)
                .withSlotOption("include-xids", true)
                .withSlotOption("skip-empty-xacts", true)
                .withStatusInterval(1, TimeUnit.SECONDS)
                .start();

        running.set(true);

        readerThread = Thread.ofVirtual().name("wal-reader-" + slotName).start(this::readLoop);
        log.info("WalReader successfully started for table: {}", targetTable);
    }

    private void readLoop() {
        log.info("Entering WAL read loop...");
        while (running.get()) {
            try {
                ByteBuffer buffer = stream.read();
                if (buffer == null) {
                    continue;
                }

                LogSequenceNumber currentLsn = stream.getLastReceiveLSN();
                log.info("Read buffer from stream at LSN {}, remaining bytes: {}", currentLsn, buffer.remaining());
                Optional<WalChangeEvent> eventOpt = decoder.decode(buffer, currentLsn.asLong());

                if (eventOpt.isPresent()) {
                    WalChangeEvent event = eventOpt.get();
                    log.info("Decoded event: op={}, table={}, lsn={}", event.operation(), event.table(), event.lsn());
                    if (targetTable == null || targetTable.equalsIgnoreCase(event.table())) {
                        log.info("Dispatching event for table {}: {}", event.table(), event.operation());
                        eventConsumer.accept(event);
                    }
                }

                // Acknowledge applied and flushed LSN to PostgreSQL
                stream.setAppliedLSN(currentLsn);
                stream.setFlushedLSN(currentLsn);

            } catch (SQLException e) {
                if (!running.get() || stream.isClosed()) {
                    log.info("Replication stream closed. Exiting read loop.");
                    break;
                }
                log.error("Database error in replication stream: {}", e.getMessage(), e);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.error("Unexpected error reading replication stream: {}", e.getMessage(), e);
            }
        }
        log.info("WAL read loop terminated.");
    }

    /**
     * Gracefully stops the replication stream.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping WalReader on slot '{}'...", slotName);
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (SQLException e) {
                log.warn("Failed to close replication stream: {}", e.getMessage());
            }
        }
        if (replicationConnection != null) {
            try {
                replicationConnection.close();
            } catch (SQLException e) {
                log.warn("Failed to close replication connection: {}", e.getMessage());
            }
        }
        log.info("WalReader stopped.");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }
}
