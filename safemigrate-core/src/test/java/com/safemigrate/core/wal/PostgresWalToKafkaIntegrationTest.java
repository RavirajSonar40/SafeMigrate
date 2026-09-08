package com.safemigrate.core.wal;

import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.kafka.WalKafkaConsumer;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresWalToKafkaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresWalToKafkaIntegrationTest.class);

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/safemigrate_test";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";

    private String testTable;
    private String slotName;
    private PostgresReplicationConnectionFactory connFactory;
    private KafkaTopicManager topicManager;
    private WalKafkaProducer kafkaProducer;
    private WalKafkaConsumer kafkaConsumer;
    private WalReader walReader;

    @BeforeEach
    void setUp() throws Exception {
        long runId = System.currentTimeMillis();
        testTable = "orders_pipe_" + runId;
        slotName = "slot_pipe_" + runId;

        connFactory = new PostgresReplicationConnectionFactory(JDBC_URL, "postgres", "password");
        topicManager = new KafkaTopicManager(KAFKA_BOOTSTRAP);
        topicManager.ensureTopicExists(testTable, 1, (short) 1);
        kafkaProducer = new WalKafkaProducer(KAFKA_BOOTSTRAP, "id");

        // Create dedicated test table with REPLICA IDENTITY FULL
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + testTable + " CASCADE;");
            stmt.execute("CREATE TABLE " + testTable + " (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "customer_id VARCHAR(64) NOT NULL, " +
                    "amount NUMERIC(10, 2) NOT NULL, " +
                    "status VARCHAR(32) NOT NULL" +
                    ");");
            stmt.execute("ALTER TABLE " + testTable + " REPLICA IDENTITY FULL;");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (walReader != null) {
            walReader.stop();
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.stop();
        }
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
        if (topicManager != null) {
            topicManager.close();
        }
        try {
            connFactory.dropReplicationSlot(slotName);
        } catch (Exception ignored) {
        }
    }

    @Test
    void shouldCaptureInsertUpdateDeleteAndConsumeFromKafka() throws Exception {
        List<WalChangeEvent> consumedEventsFromKafka = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        // 1. Setup Kafka Consumer for testTable
        String uniqueGroupId = "test-group-" + UUID.randomUUID();
        kafkaConsumer = new WalKafkaConsumer(KAFKA_BOOTSTRAP, uniqueGroupId, testTable);
        kafkaConsumer.start(event -> {
            log.info("KafkaConsumer received: op={} table={} id={} newValues={}",
                    event.operation(), event.table(), event.getPrimaryKeyValue("id"), event.newValues());
            consumedEventsFromKafka.add(event);
            latch.countDown();
        });

        // 2. Setup WAL Reader
        walReader = new WalReader(
                connFactory,
                slotName,
                testTable,
                new TestDecodingDecoder(),
                event -> {
                    log.info("WAL captured: {} on {}", event.operation(), event.table());
                    kafkaProducer.send(event);
                }
        );
        walReader.start(null);

        // Allow stream & consumer to establish
        Thread.sleep(1500);

        // 3. Execute INSERT, UPDATE, DELETE sequentially
        long generatedId;
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "postgres", "password");
             Statement stmt = conn.createStatement()) {

            // INSERT
            ResultSet rs = stmt.executeQuery(
                    "INSERT INTO " + testTable + " (customer_id, amount, status) VALUES ('cust_pipeline', 123.45, 'NEW') RETURNING id;"
            );
            rs.next();
            generatedId = rs.getLong(1);
            log.info("Executed INSERT with id={}", generatedId);

            // UPDATE
            stmt.executeUpdate(
                    "UPDATE " + testTable + " SET status = 'CONFIRMED', amount = 999.99 WHERE id = " + generatedId + ";"
            );
            log.info("Executed UPDATE on id={}", generatedId);

            // DELETE
            stmt.executeUpdate(
                    "DELETE FROM " + testTable + " WHERE id = " + generatedId + ";"
            );
            log.info("Executed DELETE on id={}", generatedId);
        }

        // 4. Wait for all 3 events to arrive from Kafka
        boolean allConsumed = latch.await(15, TimeUnit.SECONDS);
        assertThat(allConsumed)
                .as("Timed out waiting for 3 events from Kafka")
                .isTrue();

        assertThat(consumedEventsFromKafka).hasSize(3);

        // Verify INSERT
        WalChangeEvent insertEvent = consumedEventsFromKafka.get(0);
        assertThat(insertEvent.operation()).isEqualTo(OperationType.INSERT);
        assertThat(insertEvent.getPrimaryKeyValue("id")).isEqualTo(String.valueOf(generatedId));
        assertThat(insertEvent.newValues())
                .containsEntry("customer_id", "cust_pipeline")
                .containsEntry("amount", "123.45")
                .containsEntry("status", "NEW");

        // Verify UPDATE
        WalChangeEvent updateEvent = consumedEventsFromKafka.get(1);
        assertThat(updateEvent.operation()).isEqualTo(OperationType.UPDATE);
        assertThat(updateEvent.getPrimaryKeyValue("id")).isEqualTo(String.valueOf(generatedId));
        assertThat(updateEvent.newValues())
                .containsEntry("status", "CONFIRMED")
                .containsEntry("amount", "999.99");

        // Verify DELETE
        WalChangeEvent deleteEvent = consumedEventsFromKafka.get(2);
        assertThat(deleteEvent.operation()).isEqualTo(OperationType.DELETE);
        assertThat(deleteEvent.getPrimaryKeyValue("id")).isEqualTo(String.valueOf(generatedId));
    }
}
