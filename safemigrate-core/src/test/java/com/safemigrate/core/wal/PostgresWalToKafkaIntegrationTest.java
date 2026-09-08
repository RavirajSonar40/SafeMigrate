package com.safemigrate.core.wal;

import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.kafka.WalKafkaProducer;
import com.safemigrate.core.model.OperationType;
import com.safemigrate.core.model.WalChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresWalToKafkaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresWalToKafkaIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";
    private static final String SLOT_NAME = "safemigrate_integration_slot";

    private PostgresReplicationConnectionFactory connFactory;
    private KafkaTopicManager topicManager;
    private WalKafkaProducer kafkaProducer;
    private WalReader walReader;

    @BeforeEach
    void setUp() throws Exception {
        connFactory = new PostgresReplicationConnectionFactory(JDBC_URL, "postgres", "password");
        topicManager = new KafkaTopicManager(KAFKA_BOOTSTRAP);
        topicManager.ensureTopicExists("orders", 1, (short) 1);
        kafkaProducer = new WalKafkaProducer(KAFKA_BOOTSTRAP, "id");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (walReader != null) {
            walReader.stop();
        }
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
        if (topicManager != null) {
            topicManager.close();
        }
        // Clean up slot
        try {
            connFactory.dropReplicationSlot(SLOT_NAME);
        } catch (Exception e) {
            log.warn("Replication slot drop error (ignored): {}", e.getMessage());
        }
    }

    @Test
    void shouldCaptureLivePostgresInsertAndStreamToKafka() throws Exception {
        List<WalChangeEvent> capturedEvents = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        walReader = new WalReader(
                connFactory,
                SLOT_NAME,
                "orders",
                new TestDecodingDecoder(),
                event -> {
                    log.info("Test received event: {} on table {}", event.operation(), event.table());
                    capturedEvents.add(event);
                    kafkaProducer.send(event);
                    if (event.operation() == OperationType.INSERT) {
                        latch.countDown();
                    }
                }
        );

        walReader.start(null);

        // Allow stream connection to settle
        Thread.sleep(1000);

        // Perform an INSERT in the PostgreSQL orders table
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO orders (customer_id, amount, status) VALUES ('test_customer_wal', 999.99, 'PENDING');");
        }

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(capturedEvents).isNotEmpty();

        WalChangeEvent captured = capturedEvents.stream()
                .filter(e -> e.operation() == OperationType.INSERT)
                .findFirst()
                .orElseThrow();

        assertThat(captured.table()).isEqualTo("orders");
        assertThat(captured.newValues())
                .containsEntry("customer_id", "test_customer_wal")
                .containsEntry("amount", "999.99")
                .containsEntry("status", "PENDING");
    }
}
